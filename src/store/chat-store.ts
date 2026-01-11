import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  Session,
  SessionId,
  AgentId,
  Message,
  TokenUsage,
  InferenceParams,
  GeneratedImageData,
  RagReference,
  RagProgress,
  RagMetadata,
} from '../types/chat';
import { db } from '../lib/db';
import { useAgentStore } from './agent-store';
import { useApiStore } from './api-store';
import { useSettingsStore } from './settings-store';
import { createLlmClient } from '../lib/llm/factory';
import { estimateTokens } from '../features/chat/utils/token-counter';
import { performWebSearch } from '../features/chat/utils/web-search';
import { LlmClient } from '../lib/llm/types';
import * as FileSystem from 'expo-file-system/legacy';
import { MemoryManager } from '../lib/rag/memory-manager';
import { graphExtractor } from '../lib/rag/graph-extractor'; // ✅ Import KG Extractor

import { ContextManager } from '../features/chat/utils/ContextManager';
import { skillRegistry } from '../lib/skills/registry';
import { ToolCall, ToolResult, ExecutionStep, SkillContext } from '../types/skills';

// ✅ 辅助函数：从数据库查询消息归档状态
const enrichMessagesWithArchiveStatus = async (
  sessionId: string,
  messages: Message[],
): Promise<Message[]> => {
  try {
    // 查询该会话所有已归档的消息ID
    const result = await db.execute(
      'SELECT DISTINCT start_message_id, end_message_id FROM vectors WHERE session_id = ?',
      [sessionId],
    );

    const archivedMessageIds = new Set<string>();
    if (result.rows) {
      const rows = (result.rows as any)._array || (result.rows as any) || [];
      for (const row of rows) {
        if (row.start_message_id) archivedMessageIds.add(row.start_message_id);
        if (row.end_message_id) archivedMessageIds.add(row.end_message_id);
      }
    }

    // 标记消息是否已归档
    return messages.map((msg) => {
      const isArchived = archivedMessageIds.has(msg.id);
      return {
        ...msg,
        isArchived,
        vectorizationStatus: isArchived ? 'success' : undefined,
      };
    });
  } catch (e) {
    console.error('[ChatStore] Failed to enrich messages with archive status:', e);
    return messages;
  }
};

interface ChatState {
  sessions: Session[];
  activeRequests: Record<string, LlmClient>; // sessionId -> activeClient
  activeKGExtractions: Record<string, boolean>; // sessionId -> isExtractingKG
  currentGeneratingSessionId: string | null;

  addSession: (session: Session) => void;
  updateSession: (id: SessionId, updates: Partial<Session>) => void;
  deleteSession: (id: SessionId) => void;
  addMessage: (sessionId: SessionId, message: Message) => void;
  getSessionsByAgent: (agentId: AgentId) => Session[];
  getSession: (id: SessionId) => Session | undefined;

  // Actions
  // Actions
  generateMessage: (
    sessionId: SessionId,
    content: string,
    options?: {
      webSearch?: boolean;
      reasoning?: boolean;
      images?: (string | GeneratedImageData)[];
      ragOptions?: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[];
        activeFolderIds?: string[]; // ✅ 添加缺失的字段
        isGlobal?: boolean;
      };
    },
  ) => Promise<void>;
  generateSessionTitle: (sessionId: SessionId) => Promise<string | undefined>;
  abortGeneration: (sessionId: SessionId) => void;

  // Specialized update methods
  updateSessionTitle: (id: SessionId, title: string) => void;
  updateSessionPrompt: (id: SessionId, prompt: string | undefined) => void;
  updateSessionModel: (id: SessionId, modelId: string | undefined) => void;
  updateSessionOptions: (
    id: SessionId,
    options: {
      webSearch?: boolean;
      reasoning?: boolean;
      ragOptions?: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[]; // Allow updating activeDocIds
        activeFolderIds?: string[];
        isGlobal?: boolean;
      };
    },
  ) => void;
  updateSessionScrollOffset: (id: SessionId, offset: number) => void;
  updateMessageContent: (
    sessionId: SessionId,
    messageId: string,
    content: string,
    tokens?: TokenUsage,
    reasoning?: string,
    citations?: { title: string; url: string; source?: string }[],
    ragReferences?: RagReference[],
    ragReferencesLoading?: boolean,
    ragMetadata?: RagMetadata,
  ) => void;
  updateMessageProgress: (sessionId: string, messageId: string, progress: RagProgress) => void;
  updateSessionInferenceParams: (id: SessionId, params: InferenceParams) => void;
  deleteMessage: (sessionId: SessionId, messageId: string) => void;
  toggleSessionPin: (sessionId: SessionId) => void;

  updateSessionDraft: (sessionId: SessionId, draft: string | undefined) => void;
  // Deprecated: setMessagesArchived: (sessionId: SessionId, messageIds: string[]) => void;
  setVectorizationStatus: (sessionId: SessionId, messageIds: string[], status: 'processing' | 'success' | 'error') => void;
  updateMessageLayout: (sessionId: SessionId, messageId: string, height: number) => void;
  setKGExtractionStatus: (sessionId: SessionId, isExtracting: boolean) => void;
  vectorizeMessage: (sessionId: string, messageId: string) => Promise<void>;
  summarizeSession: (sessionId: string) => Promise<void>;
  regenerateMessage: (sessionId: string, messageId: string) => Promise<void>; // ✅ New Action
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get): ChatState => ({
      sessions: [],
      activeRequests: {},
      activeKGExtractions: {},
      currentGeneratingSessionId: null,

      addSession: (session) => set((state) => ({ sessions: [session, ...state.sessions] })),
      setKGExtractionStatus: (sessionId, isExtracting) =>
        set((state) => ({
          activeKGExtractions: { ...state.activeKGExtractions, [sessionId]: isExtracting },
        })),
      updateSession: (id, updates) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, ...updates } : s)),
        })),
      deleteSession: (id) =>
        set((state) => ({
          sessions: state.sessions.filter((s) => s.id !== id),
        })),
      addMessage: (sessionId, message) => {
        // 1. Update State
        set((state) => ({
          sessions: state.sessions.map((s) => {
            if (s.id === sessionId) {
              return {
                ...s,
                messages: [...s.messages, message],
                lastMessage: message.content,
                unread: s.unread || 0,
                time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
              };
            }
            return s;
          }),
        }));

        // 2. Trigger KG Extraction (Async/Background)
        // Extract both USER and ASSISTANT messages to build full context graph.
        // Optimization: Skip short messages to save tokens (e.g., "Hello", "Thanks", "Continue").
        const isWorthExtracting =
          (message.role === 'assistant' && (message.content?.length ?? 0) > 30) ||
          (message.role === 'user' && (message.content?.length ?? 0) > 15);

        if (isWorthExtracting && message.content?.trim()) {
          setTimeout(async () => {
            try {
              const session = get().getSession(sessionId);
              if (!session) return;

              // Indicate start of extraction
              get().setKGExtractionStatus(sessionId, true);

              // 1. Get Global Setting
              const { useSettingsStore } = require('../store/settings-store'); // Lazy import to avoid cycle if any
              const globalConfig = useSettingsStore.getState().globalRagConfig;

              // 2. Get Session Setting
              const sessionKgOption = session.ragOptions?.enableKnowledgeGraph;

              // 3. Determine Effective Status (Session Override > Global)
              const isKgEnabled = sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph;

              if (!isKgEnabled) {
                console.log('[ChatStore] KG Extraction disabled for this session/globally.');
                get().setKGExtractionStatus(sessionId, false);
                return;
              }

              await graphExtractor.extractAndSave(message.content, undefined, {
                sessionId,
                agentId: session.agentId,
              });
            } catch (e) {
              console.warn('[ChatStore] Background KG extraction failed:', e);
            } finally {
              // Indicate end of extraction
              get().setKGExtractionStatus(sessionId, false);
            }
          }, 100);
        }
      },
      getSessionsByAgent: (agentId) => {
        const sessions = get().sessions.filter((s) => s.agentId === agentId);
        return sessions.sort((a, b) => {
          if (a.isPinned === b.isPinned) return 0;
          return a.isPinned ? -1 : 1;
        });
      },
      getSession: (id) => {
        const session = get().sessions.find((s) => s.id === id);
        // Note: 无法在同步getter中调用异步enrichMessagesWithArchiveStatus
        // 归档状态将在组件层面按需查询
        return session;
      },

      toggleSessionPin: (id) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, isPinned: !s.isPinned } : s)),
        })),

      updateSessionTitle: (id, title) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, title } : s)),
        })),
      updateSessionPrompt: (id, prompt) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, customPrompt: prompt } : s)),
        })),
      updateSessionModel: (id, modelId) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, modelId } : s)),
        })),
      updateSessionOptions: (id, options) =>
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === id
              ? {
                ...s,
                options: { ...s.options, ...options },
                ragOptions: { ...s.ragOptions, ...options?.ragOptions }, // Merge if passed
              }
              : s,
          ),
        })),
      updateSessionScrollOffset: (id, offset) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, scrollOffset: offset } : s)),
        })),
      updateMessageContent: (
        sessionId: SessionId,
        messageId: string,
        content: string,
        tokens?: TokenUsage,
        reasoning?: string,
        citations?: { title: string; url: string; source?: string }[],
        ragReferences?: RagReference[],
        ragReferencesLoading?: boolean,
        ragMetadata?: any,
      ) =>
        set((state) => {
          const session = state.sessions.find((s) => s.id === sessionId);
          if (!session) return {};

          // 计算新增的 token（用于累加）
          const message = session.messages.find((m) => m.id === messageId);
          if (!message) return {};

          const oldTokens = message.tokens || { input: 0, output: 0, total: 0 };
          const newTokens = tokens || oldTokens;

          // 🔑 防止重复累加：只有当 tokens 真正变化时才累加
          const tokensChanged = newTokens.total !== oldTokens.total;

          // 计算 delta（增量）
          const deltaInput = newTokens.input - oldTokens.input;
          const deltaOutput = newTokens.output - oldTokens.output;
          const deltaTotal = newTokens.total - oldTokens.total;

          // 更新 session.stats.billing（累加模式）
          const currentBilling = session.stats?.billing || {
            chatInput: { count: 0, isEstimated: false },
            chatOutput: { count: 0, isEstimated: false },
            ragSystem: { count: 0, isEstimated: false },
            total: 0,
            costUSD: 0,
          };

          // 🔑 关键修复：只在 token 变化且有增量时累加
          const updatedBilling = { ...currentBilling };

          if (tokensChanged && deltaTotal > 0) {
            if (message.role === 'assistant') {
              // Assistant 消息：output 增量 + 可能包含的 RAG token
              updatedBilling.chatOutput.count += deltaOutput;

              // RAG token 在 input 中
              const ragSystemDelta = deltaTotal - deltaOutput;

              // 检测是否有 RAG 参与（通过 ragMetadata 或 ragReferences）
              // 注意：这里使用传入的新值，而非message的旧值
              const hasRag =
                (ragMetadata !== undefined ? ragMetadata : message.ragMetadata) ||
                (ragReferences !== undefined ? ragReferences : message.ragReferences);

              if (hasRag && ragSystemDelta > 0) {
                // 有 RAG 参与
                updatedBilling.ragSystem.count += ragSystemDelta;
              } else {
                updatedBilling.chatInput.count += deltaInput;
              }
            } else {
              // User 消息：只有 input
              updatedBilling.chatInput.count += deltaInput;
            }

            updatedBilling.total += deltaTotal;
          }

          return {
            sessions: state.sessions.map((s) => {
              if (s.id === sessionId) {
                return {
                  ...s,
                  messages: s.messages.map((m) =>
                    m.id === messageId
                      ? {
                        ...m,
                        content,
                        tokens: newTokens,
                        reasoning: reasoning !== undefined ? reasoning : m.reasoning,
                        citations: citations !== undefined ? citations : m.citations,
                        ragReferences:
                          ragReferences !== undefined ? ragReferences : m.ragReferences,
                        ragReferencesLoading:
                          ragReferencesLoading !== undefined
                            ? ragReferencesLoading
                            : m.ragReferencesLoading,
                        ragMetadata: ragMetadata !== undefined ? ragMetadata : m.ragMetadata,
                      }
                      : m,
                  ),
                  lastMessage: content,
                  stats: {
                    ...s.stats,
                    totalTokens: updatedBilling.total,
                    billing: updatedBilling,
                  },
                };
              }
              return s;
            }),
          };
        }),

      updateSessionInferenceParams: (id, params) =>
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === id ? { ...s, inferenceParams: { ...s.inferenceParams, ...params } } : s,
          ),
        })),

      updateSessionDraft: (id, draft) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === id ? { ...s, draft } : s)),
        })),

      updateMessageProgress: (sessionId, messageId, progress) =>
        set((state) => {
          const session = state.sessions.find((s) => s.id === sessionId);
          if (!session) return {};

          const msgIndex = session.messages.findIndex((m) => m.id === messageId);
          if (msgIndex === -1) return {};

          const updatedMessages = [...session.messages];
          updatedMessages[msgIndex] = { ...updatedMessages[msgIndex], ragProgress: progress };

          const updatedSession = { ...session, messages: updatedMessages, updatedAt: Date.now() }; // 3. Retain updatedAt

          // 2. Removed ChatStorage usage (rely on persist middleware)

          return {
            sessions: state.sessions.map((s) => (s.id === sessionId ? updatedSession : s)),
          };
        }),

      deleteMessage: (sessionId, messageId) => {
        const state = get();
        // If the session being edited is currently generating
        if (state.currentGeneratingSessionId === sessionId) {
          const session = state.sessions.find((s) => s.id === sessionId);
          if (session) {
            // If we are deleting the last message (which is usually the AI message under generation)
            const lastMsg = session.messages[session.messages.length - 1];
            if (lastMsg && lastMsg.id === messageId) {
              console.log('[ChatStore] Deleting active generating message, aborting...');
              state.abortGeneration(sessionId);
            }
          }
        }

        set((state) => ({
          sessions: state.sessions.map((s) => {
            if (s.id === sessionId) {
              return {
                ...s,
                messages: s.messages.filter((m) => m.id !== messageId),
              };
            }
            return s;
          }),
        }));
      },

      setVectorizationStatus: (sessionId, messageIds, status) => {
        set((state) => {
          const session = state.sessions.find((s) => s.id === sessionId);
          if (!session) return {};

          const idSet = new Set(messageIds);
          return {
            sessions: state.sessions.map((s) => {
              if (s.id === sessionId) {
                return {
                  ...s,
                  messages: s.messages.map((m) =>
                    idSet.has(m.id) ? {
                      ...m,
                      vectorizationStatus: status,
                      isArchived: status === 'success' ? true : m.isArchived // Update legacy flag too
                    } : m
                  ),
                  stats: s.stats,
                };
              }
              return s;
            }),
          };
        });
      },

      abortGeneration: (sessionId) => {
        const client = get().activeRequests[sessionId];
        if (client) {
          client.abort?.();
          set((state) => {
            const newRequests = { ...state.activeRequests };
            delete newRequests[sessionId];
            const isCurrent = state.currentGeneratingSessionId === sessionId;
            return {
              activeRequests: newRequests,
              currentGeneratingSessionId: isCurrent ? null : state.currentGeneratingSessionId,
            };
          });
        }
      },

      generateMessage: async (sessionId, content, options) => {
        const session = get().getSession(sessionId);
        if (!session) return;

        const agentStore = useAgentStore.getState();
        const apiStore = useApiStore.getState();

        const agent = agentStore.getAgent(session.agentId);
        if (!agent) {
          console.error('Agent not found');
          return;
        }

        // 1. Resolve Model Configuration
        const modelId = session.modelId || agent.defaultModel;
        let provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m) => m.uuid === modelId),
        );
        let modelConfig = provider?.models.find((m) => m.uuid === modelId);

        if (!provider) {
          provider = apiStore.providers.find(
            (p) => p.enabled && p.models.some((m) => m.id === modelId),
          );
          modelConfig = provider?.models.find((m) => m.id === modelId);
        }

        if (!provider || !modelConfig) {
          console.error('No enabled provider found for model:', modelId);
          return;
        }

        // Check for multimodal capability
        if (options?.images && options.images.length > 0) {
          if (!modelConfig.capabilities.vision) {
            const errorMsg = `Model "${modelConfig.name}" does not support image input. Please switch to a multimodal model (e.g. GPT-4o, Gemini Pro Vision).`;

            // Add ephemeral error message to chat
            get().addMessage(sessionId, {
              id: `err_${Date.now()}`,
              role: 'assistant',
              content: `[System Error] ${errorMsg}`,
              createdAt: Date.now(),
            });
            return;
          }
        }

        // 2. Add User Message
        const promptTokens = estimateTokens(content);

        // Normalize images
        const normalizedImages: GeneratedImageData[] | undefined = options?.images?.map((img) => {
          if (typeof img === 'string') {
            return { thumbnail: img, original: img, mime: 'image/jpeg' };
          }
          return img;
        });

        const userMsg: Message = {
          id: `msg_${Date.now()}`,
          role: 'user',
          content,
          createdAt: Date.now(),
          tokens: { input: promptTokens, output: 0, total: promptTokens },
          images: normalizedImages,
        };
        get().addMessage(sessionId, userMsg);

        set({ currentGeneratingSessionId: sessionId });

        // 3. Add Assistant Placeholder
        const assistantMsgId = `msg_ai_${Date.now()}`;
        const assistantMsg: Message = {
          id: assistantMsgId,
          role: 'assistant',
          content: '',
          createdAt: Date.now(),
          modelId: modelId,
          ragReferences: [], // Initialize RAG references
        };
        get().addMessage(sessionId, assistantMsg);

        let accumulatedContent = '';
        let accumulatedReasoning = '';
        let accumulatedCitations: { title: string; url: string; source?: string }[] | undefined =
          undefined; // Initialize correctly
        let ragReferences: RagReference[] = []; // Track RAG references safely
        let ragUsage: { ragSystem: number; isEstimated: boolean } | undefined; // Track RAG usage

        try {
          const extendedConfig = {
            ...modelConfig,
            provider: provider.type,
            apiKey: provider.apiKey,
            baseUrl: provider.baseUrl,
            // 🔑 Fix: Session params > Agent params > Default
            temperature: session.inferenceParams?.temperature ?? agent.params?.temperature ?? 0.7,
            topP: session.inferenceParams?.topP ?? agent.params?.topP ?? 1.0,
            maxTokens: session.inferenceParams?.maxTokens ?? agent.params?.maxTokens ?? 4096,
            vertexProject: provider.vertexProject,
            vertexLocation: provider.vertexLocation,
            vertexKeyJson: provider.vertexKeyJson,
          };

          const client = createLlmClient(extendedConfig as any);
          set((state) => ({ activeRequests: { ...state.activeRequests, [sessionId]: client } }));

          // Determine if we need client-side search (Non-native models)
          // Native models: gemini-*, vertex-* (usually handled by provider)
          // We assume Gemini/Vertex providers handle 'webSearch' option natively via tools
          // For others (DeepSeek/OpenAI), we do client-side search
          // Determine if we need client-side search (Non-native models)
          // Native models: gemini (gemini), google (vertex)
          // We assume Gemini/Vertex providers handle 'webSearch' option natively via tools
          // For others (DeepSeek/OpenAI), we do client-side search
          const isNativeSearch = provider.type === 'gemini' || provider.type === 'google';
          let searchContext = '';
          let initialCitations: { title: string; url: string; source?: string }[] = [];

          if (options?.webSearch && !isNativeSearch) {
            try {
              // 1. Get Search Config
              const searchConfig = useApiStore.getState().googleSearchConfig;

              // 2. Set loading state (showing "Searching..." via initialCitations hack or just wait)
              // Actually, streaming hasn't started yet, so we can just wait or show a toast?
              // Better: Just do it. The user will see "Thinking..." anyway.

              // 3. Perform Search
              const { context, sources } = await performWebSearch(
                content, // Use user query
                searchConfig?.apiKey,
                searchConfig?.cx
              );

              searchContext = context;
              initialCitations = sources;

              // ✅ 关键修复：将客户端搜索结果直接赋值给 accumulatedCitations
              // 这样后续的 scheduleUpdate 就能正确传递这些引用
              accumulatedCitations = sources;

              console.log('[ChatStore] Client-side search completed', {
                sources: sources.length,
                hasContext: !!context
              });

            } catch (err) {
              console.error('Client-side search failed', err);
              // Fallback is handled inside performWebSearch (returns mock or empty)
            }
          }

          // 3.5 RAG 检索 (Retrieval)
          let ragContext = '';

          // 🔑 关键修复：优先从session持久化配置读取，再考虑临时覆盖
          const sessionRagOptions = session.ragOptions || {};
          const tempRagOptions = options?.ragOptions || {};

          // 合并配置：临时参数可覆盖session配置（保持灵活性）
          const finalRagOptions = {
            enableMemory: tempRagOptions.enableMemory ?? sessionRagOptions.enableMemory ?? true,
            enableDocs: tempRagOptions.enableDocs ?? sessionRagOptions.enableDocs ?? false,
            activeDocIds: tempRagOptions.activeDocIds ?? sessionRagOptions.activeDocIds ?? [],
            activeFolderIds:
              tempRagOptions.activeFolderIds ?? sessionRagOptions.activeFolderIds ?? [],
            isGlobal: tempRagOptions.isGlobal ?? sessionRagOptions.isGlobal ?? false,
          };

          const isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs;
          const apiMessage = { content, images: normalizedImages };

          if (isRagEnabled) {
            try {
              // 🐛 调试日志：记录授权信息
              console.log('[RAG DEBUG] 开始检索:', {
                sessionId,
                enableMemory: finalRagOptions.enableMemory,
                enableDocs: finalRagOptions.enableDocs,
                activeDocIds: finalRagOptions.activeDocIds,
                activeFolderIds: finalRagOptions.activeFolderIds,
                isGlobal: finalRagOptions.isGlobal,
                docCount: finalRagOptions.activeDocIds?.length || 0,
                folderCount: finalRagOptions.activeFolderIds?.length || 0,
              });

              // Set loading state
              get().updateMessageContent(
                sessionId,
                assistantMsgId,
                '',
                undefined,
                undefined,
                undefined,
                [],
                true,
              );

              // For Super Assistant, force global search AND enable docs/memory
              const isSuperAssistant = sessionId === 'super_assistant';
              const effectiveRagOptions = {
                ...finalRagOptions,
                isGlobal: isSuperAssistant ? true : finalRagOptions.isGlobal,
                enableDocs: isSuperAssistant ? true : finalRagOptions.enableDocs, // 🔑 强制开启文档
                enableMemory: isSuperAssistant ? true : finalRagOptions.enableMemory, // 🔑 强制开启记忆
                ragConfig: agent.ragConfig, // ✅ 关键：传入特定助手的 RAG 配置
                onProgress: (stage: string, percentage: number) => {
                  get().updateMessageProgress(sessionId, assistantMsgId, {
                    stage: stage as any,
                    percentage,
                  });
                },
              };

              const {
                context: retrievedContext,
                references,
                metadata,
                billingUsage,
              } = await MemoryManager.retrieveContext(
                apiMessage.content,
                sessionId,
                effectiveRagOptions,
              );

              // Store for stats tracking
              if (billingUsage) {
                ragUsage = billingUsage;
              }

              ragContext = retrievedContext;
              ragReferences = references;

              // 🐛 调试日志：记录检索结果
              console.log('[RAG DEBUG] 检索完成:', {
                contextLength: ragContext.length,
                referencesCount: ragReferences.length,
                memoryRefs: ragReferences.filter((r) => r.type === 'memory').length,
                docRefs: ragReferences.filter((r) => r.type === 'doc').length,
                docIds: ragReferences.filter((r) => r.type === 'doc').map((r) => r.docId),
              });

              // Update with results and turn off loading, including metadata
              get().updateMessageContent(
                sessionId,
                assistantMsgId,
                '',
                undefined,
                undefined,
                undefined,
                ragReferences,
                false,
                metadata,
              );
            } catch (e) {
              console.error('RAG Retrieval failed:', e);
              get().updateMessageContent(
                sessionId,
                assistantMsgId,
                '',
                undefined,
                undefined,
                undefined,
                [],
                false,
              );
            }
          } else {
            console.log('[RAG DEBUG] RAG已禁用，跳过检索');
          }

          // 4. 准备上下文 (Prepare Context)
          const availableSkills = skillRegistry.getEnabledSkills();

          // Inject Tools into System Prompt (Belt and Suspenders for DeepSeek/Gemini)
          let finalSystemPrompt =
            agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');

          if (availableSkills.length > 0) {
            // 🧠 Fix: Inject FULL Schema to prevent hallucinated/empty args
            const toolsDesc = availableSkills.map(s => {
              const schemaStr = JSON.stringify(s.schema ? (s.schema as any)._def ? require('zod-to-json-schema').zodToJsonSchema(s.schema) : s.schema : {}, null, 2);
              // Fallback if zod-to-json-schema is too heavy/unavailable? 
              // Actually, let's just use a simpler description format since we don't have zod-to-json-schema installed universally perhaps.
              // We can manually iterate the Zod shape if needed, but let's try a descriptive format first.

              // Simple Schema Description
              let argsDesc = 'No arguments';
              if (s.schema && (s.schema as any).shape) {
                argsDesc = Object.entries((s.schema as any).shape).map(([key, val]: [string, any]) => {
                  const isOptional = val.isOptional && val.isOptional();
                  const desc = val.description ? ` - ${val.description}` : '';
                  return `  - ${key}${isOptional ? ' (optional)' : ' (REQUIRED)'}: ${desc}`;
                }).join('\n');
              }

              return `### ${s.name} (ID: ${s.id})\n${s.description}\nArguments:\n${argsDesc}`;
            }).join('\n\n');


            // 🧠 Conditional System Prompt Injection
            // Gemini/Vertex often fails to trigger native tools without explicit encouragement/description in the prompt.
            // OpenAI/DeepSeek/Kimi usually work better without this noise (they use the API 'tools' param effectively).
            const isGemini = modelId.toLowerCase().includes('gemini') || modelId.toLowerCase().includes('flash') || modelId.toLowerCase().includes('pro');

            let toolInstruction = '';

            if (isGemini) {
              toolInstruction = `\n\n[AVAILABLE TOOLS]
You have access to the following tools. 
CRITICAL: You MUST use the Native Function Calling mechanism. 
DO NOT simply write "I will call..." or "Calling tool...". THAT IS FAKE EXECUTION.
DO NOT invent tool names. USE ONLY THE IDs LISTED BELOW.

${toolsDesc}

[MANDATORY PLANNING]
For any complex request (e.g. generating content, research), you MUST first output a plan in this EXACT format:
<plan>
1. [Describe step 1]
2. [Describe step 2]
</plan>

[EXECUTION RULES]
1. Output the <plan> block FIRST.
2. IMMEDIATELY after the plan, call the necessary tool(s) using NATIVE FUNCTION CALLING.
3. IF A PARAMETER IS MARKED "(REQUIRED)", YOU MUST PROVIDE IT.
4. DO NOT ask for permission, just proceed.`;
            } else {
              // Ultra-light prompt for DeepSeek/OpenAI/Kimi
              // We REMOVE explicit tool descriptions and strict rules to avoid "jailbreak" detection or confusion.
              // We ONLY ask for the plan if it helps, otherwise we trust their native capability.
              toolInstruction = `\n\n[PLANNING]
If the user's request requires multiple steps or complex reasoning, please output a plan first in this format:
<plan>
1. Step 1
2. Step 2
</plan>

Then proceed to use the provided tools naturally.`;
            }

            finalSystemPrompt += toolInstruction;
          }

          // 将 RAG 上下文注入到系统提示词中
          if (ragContext) {
            finalSystemPrompt += `\n\n${ragContext}`;
          }

          let contextMsgs: any[] = [];
          // 将搜索上下文 (Web) 注入到系统提示词或作为单独的系统消息
          if (searchContext) {
            contextMsgs.push({
              role: 'system',
              content: finalSystemPrompt + '\n\n' + searchContext,
            });
          } else {
            contextMsgs.push({ role: 'system', content: finalSystemPrompt });
          }

          // Helper to format content for LLM (Text or Multimodal)
          const formatContent = async (
            msgContent: string,
            images?: GeneratedImageData[],
          ) => {
            // Priority 1: If model doesn't support vision, skip image processing entirely
            const hasVision = modelConfig?.capabilities.vision;
            if (!images || images.length === 0 || !hasVision) return msgContent;

            const parts: any[] = [{ type: 'text', text: msgContent }];
            for (const img of images) {
              try {
                // Optimization: Only read original if it's reasonably small or if necessary
                const imgPath = img.original;
                const base64 = await FileSystem.readAsStringAsync(imgPath, { encoding: 'base64' });
                parts.push({
                  type: 'image_url',
                  image_url: { url: `data:${img.mime || 'image/jpeg'};base64,${base64}` },
                });
              } catch (e) {
                console.error('Failed to read image:', img.original, e);
              }
            }
            return parts;
          };

          // ✅ 关键修复 1: 注入历史消息 (Fix Context Loss)
          // session.messages 在函数开始时捕获，此时尚未添加当前的用户消息，正是我们要的“历史”
          // 我们取最近的 N 条消息作为上下文
          // 动态读取高级 RAG 配置中的上下文窗口大小 (Default: 20)
          const contextWindowSize = useSettingsStore.getState().globalRagConfig.contextWindow || 15;
          const historyMsgs = session.messages.slice(-contextWindowSize);

          for (const msg of historyMsgs) {
            // Skip system messages locally if any, though usually not stored in session
            if (msg.role === 'system') continue;

            // Format content (handle images if any, checking model capabilities)
            const formattedContent = await formatContent(msg.content, msg.images);

            // 如果是 Tool Result 的历史记录，需要特殊处理 (TODO)，目前仅处理文本对话
            // 简单起见，我们暂不重建复杂的 Tool/Function History，以避免 Token 爆炸
            // 对于 DeepSeek/Reasoning 模型，纯文本对话历史通常足够
            contextMsgs.push({
              role: msg.role,
              content: formattedContent
            });
          }

          // Add user message to context
          contextMsgs.push({ role: 'user', content: await formatContent(apiMessage.content, apiMessage.images) });

          // =====================================================================================
          // Phase 4: Agentic Loop Implementation
          // =====================================================================================
          const MAX_LOOP_COUNT = useSettingsStore.getState().maxLoopCount || 5;
          let loopCount = 0;
          let currentAssistantMsgId = assistantMsgId; // Track current assistant message
          let accumulatedUsage: { input: number; output: number; total: number } | undefined;

          // Loop Context
          let currentMessages = [...contextMsgs];
          let loopExecutionSteps: ExecutionStep[] = [];

          // ✅ Pre-populate with Client-Side Search (Pre-Search) if available
          if (accumulatedCitations && accumulatedCitations.length > 0) {
            const searchStepId = `search_init_${Date.now()}`;
            // 1. Tool Call Simulation
            loopExecutionSteps.push({
              id: searchStepId,
              type: 'tool_call',
              toolName: 'web_search',
              toolArgs: { query: content.substring(0, 50) + (content.length > 50 ? '...' : '') }, // Approximate query
              timestamp: Date.now()
            });
            // 2. Tool Result Simulation
            loopExecutionSteps.push({
              id: `res_${searchStepId}`,
              type: 'tool_result',
              toolName: 'web_search',
              content: `Found ${accumulatedCitations.length} sources.`,
              data: { sources: accumulatedCitations },
              timestamp: Date.now() + 100
            });

            // Immediately update UI with these initial steps
            get().updateMessageContent(
              sessionId,
              currentAssistantMsgId,
              accumulatedContent,
              undefined,
              accumulatedReasoning,
              accumulatedCitations,
              ragReferences,
              false,
              undefined,
            );
            set(state => ({
              sessions: state.sessions.map(s => s.id === sessionId ? {
                ...s,
                messages: s.messages.map(m => m.id === currentAssistantMsgId ? { ...m, executionSteps: loopExecutionSteps } : m)
              } : s)
            }));
          }

          // Helper to update execution steps in store
          const updateSteps = (newStep: ExecutionStep) => {
            const index = loopExecutionSteps.findIndex(s => s.id === newStep.id);
            if (index > -1) {
              const updatedSteps = [...loopExecutionSteps];
              updatedSteps[index] = newStep;
              loopExecutionSteps = updatedSteps;
            } else {
              loopExecutionSteps = [...loopExecutionSteps, newStep];
            }

            get().updateMessageContent(
              sessionId,
              currentAssistantMsgId,
              accumulatedContent,
              undefined,
              accumulatedReasoning,
              accumulatedCitations,
              ragReferences,
              false,
              undefined,
            );
            set(state => ({
              sessions: state.sessions.map(s => s.id === sessionId ? {
                ...s,
                messages: s.messages.map(m => m.id === currentAssistantMsgId ? { ...m, executionSteps: loopExecutionSteps } : m)
              } : s)
            }));
          };


          while (loopCount < MAX_LOOP_COUNT) {
            loopCount++;
            console.log(`[AgentLoop] Turn ${loopCount}/${MAX_LOOP_COUNT}`);

            // 4. Get Enabled Skills
            const availableSkills = skillRegistry.getEnabledSkills();
            console.log('[AgentLoop] Available Skills:', availableSkills.map(s => s.id));


            // 5. Stream Chat
            let toolCalls: ToolCall[] | undefined;
            let reasoningFromThisTurn = '';
            let turnContent = '';

            let planParsed = false; // 🧠 Planner State
            let shouldBreakLoop = false;
            let potentialJson = '';
            let match: RegExpMatchArray | null = null;

            // 🔑 关键修复 2: 分离并增加限流间隔 (Fix UI Freeze)
            // 避免高频 Zustand 更新阻塞 JS 线程 (特别是 React Native)
            // Content: 200ms (5fps) - 足够人眼阅读，且大幅减轻渲染压力
            // Timeline: 500ms (2fps) - 思考过程不需要极高频刷新
            let lastContentUpdateTime = 0;
            let lastTimelineUpdateTime = 0;
            const CONTENT_UPDATE_INTERVAL = 200;
            const TIMELINE_UPDATE_INTERVAL = 500;

            await client.streamChat(
              currentMessages as any,
              (token) => {
                // Check for abort
                const currentState = get();
                if (!currentState.activeRequests[sessionId]) return;

                const now = Date.now();

                // 5.0 Capture Usage
                if (token.usage) {
                  accumulatedUsage = token.usage;
                }

                // 5.1 Handle Content
                if (token.content) {
                  turnContent += token.content;
                  accumulatedContent += token.content;

                  // 🧠 Planner: Detect <plan> blocks
                  // Only parse once per turn to avoid redundant processing
                  if (!planParsed) {
                    const planMatch = accumulatedContent.match(/<plan>\s*([\s\S]*?)\s*<\/plan>/i);
                    if (planMatch) {
                      const planText = planMatch[1];
                      console.log('[AgentLoop] Plan detected:', planText);

                      const lines = planText.split('\n')
                        .map(l => l.trim())
                        .filter(l => l.length > 0);

                      lines.forEach((line, index) => {
                        // Clean "1. " prefix if present
                        const content = line.replace(/^\d+[\.\)]\s*/, '');
                        updateSteps({
                          id: `plan_step_${loopCount}_${index}`,
                          type: 'plan_item',
                          content: content,
                          timestamp: Date.now()
                        });
                      });
                      planParsed = true;
                      // Note: We don't remove it from accumulatedContent here (for memory consistency), 
                      // but we strip it from UI display below.
                    }
                  }

                  // 🔑 Throttled Content Update
                  if (now - lastContentUpdateTime > CONTENT_UPDATE_INTERVAL) {
                    // 🧠 Planner: Hide <plan> block from bubble
                    const displayContent = accumulatedContent.replace(/<plan>[\s\S]*?<\/plan>/gi, '').trim();

                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      displayContent,
                      token.usage
                    );
                    lastContentUpdateTime = now;
                  }
                }

                // 5.2 Handle Reasoning
                if (token.reasoning) {
                  reasoningFromThisTurn += token.reasoning;

                  // 🔑 Throttled Timeline Update
                  if (now - lastTimelineUpdateTime > TIMELINE_UPDATE_INTERVAL) {
                    const stepId = `think_turn_${loopCount}`;
                    updateSteps({
                      id: stepId,
                      type: 'thinking',
                      content: reasoningFromThisTurn,
                      timestamp: Date.now()
                    });

                    // 🧹 Keep bubble reasoning empty if it's in timeline
                    // We don't force update content here, just the steps logic inside updateSteps triggers set
                    const displayContent = accumulatedContent.replace(/<plan>[\s\S]*?<\/plan>/gi, '').trim();
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      displayContent,
                      token.usage,
                      '' // Clear bubble reasoning
                    );
                    lastTimelineUpdateTime = now;
                  }
                }

                // 5.3 Handle Tool Calls
                if (token.toolCalls) {
                  toolCalls = token.toolCalls;
                }
              },
              (error) => { console.warn('Stream error', error); },
              {
                skills: availableSkills,
                reasoning: true, // Enable reasoning/thinking mode
                inferenceParams: {
                  temperature: agent.params?.temperature || 0.7,
                  maxTokens: agent.params?.maxTokens,
                  topP: agent.params?.topP,
                  frequencyPenalty: agent.params?.frequencyPenalty,
                  presencePenalty: agent.params?.presencePenalty,
                },
              }
            );

            // 🏁 强制最后一次同步，确保内容完整性
            const finalDisplayContent = accumulatedContent.replace(/<plan>[\s\S]*?<\/plan>/gi, '').trim();
            get().updateMessageContent(
              sessionId,
              currentAssistantMsgId,
              finalDisplayContent,
              accumulatedUsage
            );

            if (reasoningFromThisTurn) {
              updateSteps({
                id: `think_turn_${loopCount}`,
                type: 'thinking',
                content: reasoningFromThisTurn,
                timestamp: Date.now()
              });
            }

            // 6. Output Processing


            // Fallback: Check if model outputted tool calls as JSON in text (DeepSeek/Legacy/ReAct behavior)
            if (!toolCalls || toolCalls.length === 0) {
              // console.log('[AgentLoop] No native tool calls. Checking content fallback.');
              // Helper to add unique calls
              let extractedCalls: ToolCall[] = [];

              // Helper to add unique calls
              const addCall = (name: string, args: any) => {
                if (!extractedCalls.find(c => c.name === name && JSON.stringify(c.arguments) === JSON.stringify(args))) {
                  extractedCalls.push({
                    id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                    name: name,
                    arguments: args
                  });
                }
              };

              // Strategy 1: XML-based Tool Calls (DeepSeek/Kimi/Reasoner)
              // Kimi: <tool_calls>[JSON]</tool_calls>
              // DeepSeek Reasoner: <call tool="name"><tool_input>JSON</tool_input></call>
              // DeepSeek Chat: <tool_call><function_name>name</function_name><parameters><key>val</key></parameters></tool_call>

              // 1.1 Match Kimi/Generic XML Wrapper containing JSON
              const xmlJsonRegex = /<(?:tool_code|tool_calls|tools)>([\s\S]*?)<\/(?:tool_code|tool_calls|tools)>/gi;
              let xmlMatch;
              while ((xmlMatch = xmlJsonRegex.exec(turnContent)) !== null) {
                const inner = xmlMatch[1].trim();
                // Try parsing array or object
                try {
                  const parsed = JSON.parse(inner);
                  if (Array.isArray(parsed)) {
                    parsed.forEach(c => {
                      if (c.function?.name) addCall(c.function.name, c.function.arguments);
                      else if (c.name && c.arguments) addCall(c.name, c.arguments); // Simplify Kimi format check
                      else if (c.id && c.arguments) {
                        // Kimi format: {id, type, function: {name, arguments}} or just arguments?
                        // Screenshot shows: { "id": "...", "arguments": {...} } - wait, name is missing in screenshot 4? 
                        // Ah in screenshot 4 it shows { "id": "query_vector_db", "arguments": ... } using ID as name?
                        // Let's assume ID might be name if name missing.
                        addCall(c.function?.name || c.id || c.name, c.arguments || c.parameters);
                      }
                    });
                  } else if (typeof parsed === 'object') {
                    if (parsed.name || parsed.function?.name) {
                      addCall(parsed.function?.name || parsed.name, parsed.function?.arguments || parsed.arguments);
                    }
                  }
                } catch (e) { /* Not JSON */ }
              }

              // 1.2 Match DeepSeek Reasoner: <call tool="...">
              const reasonerRegex = /<call\s+tool="([^"]+)">([\s\S]*?)<\/call>/gi;
              while ((xmlMatch = reasonerRegex.exec(turnContent)) !== null) {
                const toolName = xmlMatch[1];
                const inner = xmlMatch[2].trim();
                // Extract JSON from <tool_input> if present, or raw inner
                const inputMatch = /<tool_input>([\s\S]*?)<\/tool_input>/i.exec(inner);
                const jsonStr = inputMatch ? inputMatch[1] : inner;
                try {
                  addCall(toolName, JSON.parse(jsonStr));
                } catch (e) {
                  // Maybe it's not JSON but params?
                }
              }

              // 1.3 Match DeepSeek Chat: <tool_call> XML structure
              const deepSeekXmlRegex = /<tool_call>([\s\S]*?)<\/tool_call>/gi;
              while ((xmlMatch = deepSeekXmlRegex.exec(turnContent)) !== null) {
                const inner = xmlMatch[1];
                const nameMatch = /<function_name>([\s\S]*?)<\/function_name>/i.exec(inner);
                const paramsMatch = /<parameters>([\s\S]*?)<\/parameters>/i.exec(inner);
                if (nameMatch && paramsMatch) {
                  const name = nameMatch[1].trim();
                  const paramsInner = paramsMatch[1].trim();
                  // Naive XML param parser: <key>val</key>
                  const args: any = {};
                  const argRegex = /<([^>]+)>([\s\S]*?)<\/\1>/g;
                  let argMatch;
                  while ((argMatch = argRegex.exec(paramsInner)) !== null) {
                    args[argMatch[1]] = argMatch[2].trim(); // All strings, but usually fine for our tools
                  }
                  // Fallback if args is empty but paramsInner has content (maybe it is JSON?)
                  if (Object.keys(args).length === 0 && paramsInner.startsWith('{')) {
                    try { Object.assign(args, JSON.parse(paramsInner)); } catch (e) { }
                  }
                  if (name) addCall(name, args);
                }
              }

              // Strategy 2: Markdown Code Blocks (Legacy)
              if (extractedCalls.length === 0) {
                const jsonBlockRegex = /```(?:json)?\s*([\s\S]*?)\s*```/yi;
                const match = turnContent.match(jsonBlockRegex);
                if (match && match[1]) {
                  try {
                    const parsed = JSON.parse(match[1]);
                    // Detect format: Single tool call {action, action_input} or Array or {tool, args}
                    if (parsed.tool && parsed.args) addCall(parsed.tool, parsed.args);
                    else if (parsed.action && parsed.action_input) addCall(parsed.action, parsed.action_input);
                    else if (Array.isArray(parsed)) parsed.forEach(p => addCall(p.tool || p.name, p.args || p.arguments));
                  } catch (e) { }
                }
              }

              // Strategy 3: Naked JSON Search (Last Resort)
              // Only if no calls found yet, verify content has {"action":...} or similar keywords to capture embedded JSON
              if (extractedCalls.length === 0 && (turnContent.includes('"action"') || turnContent.includes('"tool"'))) {
                // Re-use smart JSON extractor logic or simplified regex
                const jsonRegex = /\{(?:[^{}]|{[^{}]*})*\}/g; // Simple nested brace matcher
                let match;
                while ((match = jsonRegex.exec(turnContent)) !== null) {
                  try {
                    const parsed = JSON.parse(match[0]);
                    if (parsed.tool && parsed.args) addCall(parsed.tool, parsed.args);
                    else if (parsed.action && parsed.action_input) addCall(parsed.action, parsed.action_input);
                  } catch (e) { }
                }
              }

              if (extractedCalls.length > 0) {
                console.log(`[AgentLoop] Fallback Parser found ${extractedCalls.length} calls:`, extractedCalls.map(c => c.name));
                toolCalls = extractedCalls;
              }
            }

            // Fallback Phase 2: Embedded JSON check (Waterfall if XML failed)
            if (!toolCalls || toolCalls.length === 0) {
              const firstBrace = turnContent.indexOf('{');
              const lastBrace = turnContent.lastIndexOf('}');

              if (firstBrace !== -1 && lastBrace > firstBrace) {
                const candidate = turnContent.substring(firstBrace, lastBrace + 1);
                if (candidate.includes('"action"') || candidate.includes('"tool"') || candidate.includes('"function"')) {
                  potentialJson = candidate;
                }
              }
            }

            if (potentialJson) {
              try {
                const jsonContent = JSON.parse(potentialJson);

                // Case A: Standard tool_calls wrapper
                if (jsonContent.tool_calls) {
                  toolCalls = jsonContent.tool_calls.map((tc: any) => ({
                    id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
                    name: tc.function.name,
                    arguments: typeof tc.function.arguments === 'string' ? JSON.parse(tc.function.arguments) : tc.function.arguments
                  }));
                }
                // Case B: Single function/tool wrapper
                else if (jsonContent.function || jsonContent.tool) {
                  const fn = jsonContent.function || jsonContent.tool;
                  if (typeof fn === 'string') {
                    toolCalls = [{
                      id: `call_${Date.now()}`,
                      name: fn,
                      arguments: jsonContent.parameters || jsonContent.arguments || {}
                    }];
                  } else if (fn.name) {
                    toolCalls = [{
                      id: `call_${Date.now()}`,
                      name: fn.name,
                      arguments: typeof fn.arguments === 'string' ? JSON.parse(fn.arguments) : fn.arguments
                    }];
                  }
                }
                // Case C: ReAct
                else if (jsonContent.action && jsonContent.action !== 'Final Answer') {
                  toolCalls = [{
                    id: `call_${Date.now()}`,
                    name: jsonContent.action,
                    arguments: typeof jsonContent.action_input === 'string' ? JSON.parse(jsonContent.action_input) : jsonContent.action_input
                  }];
                }

                if (toolCalls && toolCalls.length > 0) {
                  if (potentialJson === turnContent.trim()) {
                    turnContent = '';
                  } else {
                    turnContent = turnContent.replace(potentialJson, '').trim();
                  }
                  accumulatedContent = turnContent;
                  get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, accumulatedUsage);
                }
              } catch (e) { }
            }

            // Strategy 3: Text Patterns
            if (!toolCalls || toolCalls.length === 0) {
              const callPattern = /call:([\w_]+)\(([\s\S]*?)\)/gi;
              let callMatch;
              const extractedCalls: ToolCall[] = [];

              while ((callMatch = callPattern.exec(turnContent)) !== null) {
                const funcName = callMatch[1];
                const argsStr = callMatch[2];
                const args: Record<string, any> = {};

                // Simple arg parsing logic [OMITTED DETAILS FOR BREVITY, RESTORING KEY LOGIC]
                const argPattern = /([\w_]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\d+(?:\.\d+)?)|(true|false|null))/gi;
                let argMatch;
                while ((argMatch = argPattern.exec(argsStr)) !== null) {
                  const key = argMatch[1];
                  const val = argMatch[2] || argMatch[3] || argMatch[4] || argMatch[5];
                  args[key] = val; // simplified for restoration
                }
                if (Object.keys(args).length === 0 && argsStr.trim()) args['query'] = argsStr.trim();

                extractedCalls.push({
                  id: `txt_call_${Date.now()}_${extractedCalls.length}`,
                  name: funcName,
                  arguments: args
                });
                turnContent = turnContent.replace(callMatch[0], '').trim();
              }

              if (extractedCalls.length > 0) {
                toolCalls = extractedCalls;
                accumulatedContent = turnContent;
                get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, accumulatedUsage, '');
              }
            }

            // Final Processing & Execution
            if (reasoningFromThisTurn) {
              accumulatedReasoning = '';
              get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, accumulatedUsage, '');
            }

            if (toolCalls && toolCalls.length > 0) {
              // Plan Detection
              if (turnContent && turnContent.length > 0) {
                updateSteps({
                  id: `plan_${Date.now()}`,
                  type: 'thinking',
                  content: `Plan: ${turnContent}`,
                  timestamp: Date.now()
                });
                accumulatedContent = accumulatedContent.replace(turnContent, '').trim();
                get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, accumulatedUsage, '');
              }

              // Add to History
              currentMessages.push({
                role: 'assistant',
                content: turnContent || '',
                reasoning: reasoningFromThisTurn,
                tool_calls: toolCalls.map(tc => ({
                  id: tc.id,
                  type: 'function',
                  function: { name: tc.name, arguments: JSON.stringify(tc.arguments) }
                }))
              } as any);

              // Execute
              for (const tc of toolCalls) {
                const skill = skillRegistry.getSkill(tc.name);
                const stepId = `step_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
                updateSteps({ id: stepId, type: 'tool_call', toolName: tc.name, toolArgs: tc.arguments, timestamp: Date.now() });

                let result: ToolResult;
                try {
                  if (skill) {
                    result = await skill.execute(tc.arguments, { sessionId, agentId: agent.id });
                  } else {
                    result = { id: tc.id, content: `Error: Skill ${tc.name} not found`, status: 'error' };
                  }
                } catch (e: any) {
                  result = { id: tc.id, content: `Error: ${e.message}`, status: 'error' };
                }

                updateSteps({
                  id: `res_${stepId}`,
                  type: result.status === 'success' ? 'tool_result' : 'error',
                  toolName: tc.name,
                  content: result.content,
                  data: result.data,
                  timestamp: Date.now()
                });

                currentMessages.push({
                  role: 'tool',
                  tool_call_id: tc.id,
                  content: result.content,
                  name: tc.name
                } as any);
              }
              accumulatedContent += '\n\n';
            } else {
              console.log('[AgentLoop] Final answer received. Stopping.');
              shouldBreakLoop = true;
            }



            if (shouldBreakLoop) break;
          }

          // =====================================================================================
          // Phase 5: Post-Processing
          // =====================================================================================

          // Re-calculate context tokens for stats fallback
          const activeWindowSize = agent.ragConfig?.contextWindow || 10;
          const contextText = contextMsgs.map((m: any) => {
            if (typeof m.content === 'string') return m.content;
            return (m.content as any[]).map((p: any) => (p.type === 'text' ? p.text : '')).join('\n');
          }).join('\n');
          const totalContextTokens = estimateTokens(contextText);

          // 1. RAG Archiving
          if (finalRagOptions.enableMemory !== false) {
            get().setVectorizationStatus(sessionId, [userMsg.id, assistantMsgId], 'processing');
            try {
              const archiveStartTime = Date.now();
              const { updateProcessingState } = await import('../store/rag-store').then((m) => m.useRagStore.getState());
              updateProcessingState({ sessionId, status: 'chunking', startTime: archiveStartTime, chunks: [] }, assistantMsgId);
              await new Promise((resolve) => setTimeout(resolve, 0));

              await MemoryManager.addTurnToMemory(sessionId, content, accumulatedContent, userMsg.id, assistantMsgId);

              const elapsed = Date.now() - archiveStartTime;
              if (elapsed < 800) await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));

              updateProcessingState({ sessionId, status: 'archived', chunks: [] }, assistantMsgId);
              get().setVectorizationStatus(sessionId, [userMsg.id, assistantMsgId], 'success');
            } catch (e) {
              console.error('[RAG] Archive failed:', e);
              get().setVectorizationStatus(sessionId, [userMsg.id, assistantMsgId], 'error');
              const { updateProcessingState } = await import('../store/rag-store').then((m) => m.useRagStore.getState());
              updateProcessingState({ sessionId, status: 'error' }, assistantMsgId);
            }
          }

          // 2. KG Extraction
          if (accumulatedContent.trim()) {
            setTimeout(async () => {
              try {
                const { useSettingsStore } = require('../store/settings-store');
                const globalConfig = useSettingsStore.getState().globalRagConfig;
                const session = get().getSession(sessionId);
                if (!session) return;

                const isSuperAssistant = sessionId === 'super_assistant';
                const sessionKgOption = session.ragOptions?.enableKnowledgeGraph;
                const isKgEnabled = isSuperAssistant || (sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph);

                if (!isKgEnabled) return;

                const costStrategy = agent?.ragConfig?.costStrategy || globalConfig.costStrategy || 'summary-first';
                if (costStrategy === 'on-demand' && !isSuperAssistant) return;

                get().setKGExtractionStatus(sessionId, true);
                await graphExtractor.extractAndSave(accumulatedContent, undefined, { sessionId, agentId: session.agentId });
              } catch (e) {
                console.warn('[ChatStore] AI Response KG extraction failed:', e);
              } finally {
                get().setKGExtractionStatus(sessionId, false);
              }
            }, 500);
          }

          // 3. Summarization
          try {
            const currentMessages = get().getSession(sessionId)?.messages || [];
            const contentMessages = currentMessages.filter((m: { role: string; }) => m.role !== 'system');
            const summariesResult = await db.execute('SELECT start_message_id, end_message_id FROM context_summaries WHERE session_id = ?', [sessionId]);
            const summarizedMessageIds = new Set<string>();
            if (summariesResult.rows) {
              const rows = (summariesResult.rows as any)._array || (summariesResult.rows as any) || [];
              for (const row of rows) {
                if (row.start_message_id && row.end_message_id) {
                  const startIdx = contentMessages.findIndex((m) => m.id === row.start_message_id);
                  const endIdx = contentMessages.findIndex((m) => m.id === row.end_message_id);
                  if (startIdx !== -1 && endIdx !== -1) {
                    for (let i = startIdx; i <= endIdx; i++) summarizedMessageIds.add(contentMessages[i].id);
                  }
                }
              }
            }

            const newMessagesCount = contentMessages.filter((m) => !summarizedMessageIds.has(m.id)).length;
            const summaryThreshold = agent.ragConfig?.summaryThreshold || 20;
            if (newMessagesCount > activeWindowSize + summaryThreshold) {
              const summaryStartTime = Date.now();
              const { updateProcessingState } = await import('../store/rag-store').then((m) => m.useRagStore.getState());
              updateProcessingState({ sessionId, status: 'summarizing', startTime: summaryStartTime }, assistantMsgId);
              await new Promise((resolve) => setTimeout(resolve, 0));

              const beforeCount = await db.execute('SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?', [sessionId]);
              const beforeSummaryCount = (beforeCount.rows as any)?._array?.[0]?.count || (beforeCount.rows as any)?.[0]?.count || 0;

              await ContextManager.checkAndSummarize(sessionId, currentMessages, agent);

              const elapsed = Date.now() - summaryStartTime;
              if (elapsed < 800) await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));

              const afterCount = await db.execute('SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?', [sessionId]);
              const afterSummaryCount = (afterCount.rows as any)?._array?.[0]?.count || (afterCount.rows as any)?.[0]?.count || 0;

              if (afterSummaryCount > beforeSummaryCount) {
                updateProcessingState({ sessionId, status: 'summarized', summary: '' }, assistantMsgId);
              } else {
                updateProcessingState({ sessionId, status: 'idle' }, assistantMsgId);
              }
            }
          } catch (e) { console.error('[RAG] Summarization failed', e); }

          // 4. Stats & Title
          const latestMsg = get().getSession(sessionId)?.messages.find((m) => m.id === assistantMsgId);
          let finalUsage = accumulatedUsage || latestMsg?.tokens;

          const billingUsage = {
            chatInput: { count: finalUsage ? finalUsage.input : totalContextTokens, isEstimated: !finalUsage },
            chatOutput: { count: finalUsage ? finalUsage.output : estimateTokens(accumulatedContent), isEstimated: !finalUsage },
            ragSystem: ragUsage ? { count: ragUsage.ragSystem, isEstimated: ragUsage.isEstimated } : { count: 0, isEstimated: false },
            total: (finalUsage ? finalUsage.total : totalContextTokens + estimateTokens(accumulatedContent)) + (ragUsage?.ragSystem || 0)
          };

          get().updateSession(sessionId, { stats: { totalTokens: billingUsage.total, billing: billingUsage } });

          try {
            const { useTokenStatsStore } = await import('./token-stats-store');
            useTokenStatsStore.getState().trackUsage({ modelId, usage: billingUsage });
          } catch (e) { }

          if (session.messages.length <= 1 || session.title === agent.name || session.title === 'New Conversation') {
            get().updateSessionTitle(sessionId, content.substring(0, 30) + (content.length > 30 ? '...' : ''));
          }

        } catch (e: any) {
          // ... error handling
          console.error('Agent loop failed', e);
          get().addMessage(sessionId, {
            id: `sys_${Date.now()}`,
            role: 'assistant',
            content: `[System Error] ${e.message || 'Unknown error'}`,
            createdAt: Date.now()
          });
        } finally {
          // Cleanup active request and ensure loading flags are cleared
          set((state: ChatState) => {
            const newRequests = { ...state.activeRequests };
            delete newRequests[sessionId];

            // Clear loading flags on the message to avoid stuck animations
            const updatedSessions = state.sessions.map((s) => {
              if (s.id === sessionId) {
                return {
                  ...s,
                  messages: s.messages.map((m) =>
                    m.id === assistantMsgId
                      ? { ...m, ragReferencesLoading: false, status: m.status === 'streaming' ? 'sent' : m.status }
                      : m
                  ),
                };
              }
              return s;
            });

            return {
              activeRequests: newRequests,
              sessions: updatedSessions,
              currentGeneratingSessionId: state.currentGeneratingSessionId === sessionId ? null : state.currentGeneratingSessionId,
            };
          });
        }
      },





      updateMessageLayout: (sessionId: SessionId, messageId: string, height: number) => {
        const session = get().sessions.find((s) => s.id === sessionId);
        if (!session) return;

        const message = session.messages.find((m) => m.id === messageId);
        // 只有当高度未设置，或高度差异超过 2px 时才更新，避免微小抖动导致的频繁写入
        if (message && (!message.layoutHeight || Math.abs(message.layoutHeight - height) > 2)) {
          // 使用 split 更新以避免全量刷新
          const messages = session.messages.map((m) =>
            m.id === messageId ? { ...m, layoutHeight: height } : m,
          );

          // 直接复用 Zustand 的 set
          set((state: ChatState) => ({
            sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, messages } : s)),
          }));
        }
      },

      generateSessionTitle: async (sessionId: any) => {
        const session = get().getSession(sessionId);
        if (!session || session.messages.length === 0) return undefined;

        const agentStore = useAgentStore.getState();
        const apiStore = useApiStore.getState();
        const agent = agentStore.getAgent(session.agentId);
        const modelId = session.modelId || agent?.defaultModel;

        if (!modelId) return undefined;

        let provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m) => m.uuid === modelId),
        );
        let modelConfig = provider?.models.find((m) => m.uuid === modelId);

        if (!provider) {
          provider = apiStore.providers.find(
            (p) => p.enabled && p.models.some((m) => m.id === modelId),
          );
          modelConfig = provider?.models.find((m) => m.id === modelId);
        }

        if (!provider || !modelConfig) return undefined;

        try {
          const extendedConfig = {
            ...modelConfig,
            provider: provider.type,
            apiKey: provider.apiKey,
            baseUrl: provider.baseUrl,
            vertexProject: provider.vertexProject,
            vertexLocation: provider.vertexLocation,
            vertexKeyJson: provider.vertexKeyJson,
          };

          const client = createLlmClient(extendedConfig as any);

          // 提取最近的 4 条消息作为上下文
          const recentMessages = session.messages
            .slice(-4)
            .map((m: { role: any; content: any; }) => `${m.role}: ${m.content}`)
            .join('\n');
          const prompt = `Based on the conversation content, summarize a very concise title (less than 6 Chinese characters). DO NOT include quotation marks or phrases like "Title: ". Output the title directly.\n\nConversation content:\n${recentMessages}`;

          let generatedTitle = '';
          await new Promise<void>((resolve, reject) => {
            client
              .streamChat(
                [{ role: 'user', content: prompt }],
                (chunk) => {
                  if (chunk.content) generatedTitle += chunk.content;
                },
                (err) => {
                  reject(err);
                },
              )
              .then(resolve)
              .catch(reject);
          });

          const title = generatedTitle.trim().replace(/^["']|["']$/g, '');

          if (title) {
            get().updateSessionTitle(sessionId, title);
            return title;
          }
        } catch (error) {
          console.error('Failed to generate title:', error);
        }
        return undefined;
      },

      vectorizeMessage: async (sessionId: any, messageId: any) => {
        const session = get().getSession(sessionId);
        if (!session) return;
        const message = session.messages.find((m) => m.id === messageId);
        if (!message || !message.content) return;

        try {
          const { MemoryManager } = await import('../lib/rag/memory-manager');
          await MemoryManager.upsertMemory({
            id: messageId,
            content: message.content,
            sessionId,
            role: message.role,
            createdAt: message.createdAt,
            type: 'message',
            usage: 0,
          }, session.agentId);
          console.log('[ChatStore] Message manually vectorized:', messageId);
        } catch (e) {
          console.error('[ChatStore] Failed to vectorize message:', e);
          throw e;
        }
      },

      regenerateMessage: async (sessionId: string, messageId: any) => {
        const state = get();
        const session = state.getSession(sessionId);
        if (!session) return;

        // 1. Find message and predecessor
        const msgIndex = session.messages.findIndex((m) => m.id === messageId);
        if (msgIndex <= 0) {
          console.error('Cannot regenerate: Message not found or is first message');
          return;
        }

        const targetMsg = session.messages[msgIndex];
        const userMsg = session.messages[msgIndex - 1];

        if (targetMsg.role !== 'assistant' || userMsg.role !== 'user') {
          console.error('Cannot regenerate: Invalid message sequence');
          return;
        }

        // 2. Reset Target Message & Truncate History
        // Remove all messages AFTER the target message
        // Clear content of the target message
        set((state: ChatState) => ({
          sessions: state.sessions.map((s) => {
            if (s.id === sessionId) {
              const truncatedMessages = s.messages.slice(0, msgIndex + 1) as Message[];
              // Reset the last message (target)
              truncatedMessages[msgIndex] = {
                ...truncatedMessages[msgIndex],
                content: '',
                reasoning: undefined,
                citations: undefined,
                ragReferences: [],
                status: 'streaming', // Set generic status
                tokens: undefined, // Clear token stats
                executionSteps: undefined, // ✅ Reset tool execution timeline
              };
              return { ...s, messages: truncatedMessages };
            }
            return s;
          }),
          currentGeneratingSessionId: sessionId,
        }));

        // 3. Reuse Generation Logic (Copied from generateMessage)
        const agentStore = useAgentStore.getState();
        const apiStore = useApiStore.getState();
        const agent = agentStore.getAgent(session.agentId);
        if (!agent) return;

        // Resolve Model
        const modelId = session.modelId || agent.defaultModel; // Use session model
        let provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m) => m.uuid === modelId),
        );
        let modelConfig = provider?.models.find((m) => m.uuid === modelId);

        if (!provider) {
          provider = apiStore.providers.find(
            (p) => p.enabled && p.models.some((m) => m.id === modelId),
          );
          modelConfig = provider?.models.find((m) => m.id === modelId);
        }

        if (!provider || !modelConfig) return;

        // 4. Setup Client & Config
        const extendedConfig = {
          ...modelConfig,
          provider: provider.type,
          apiKey: provider.apiKey,
          baseUrl: provider.baseUrl,
          temperature: agent.params?.temperature ?? 0.7,
          vertexProject: provider.vertexProject,
          vertexLocation: provider.vertexLocation,
          vertexKeyJson: provider.vertexKeyJson,
        };

        const client = createLlmClient(extendedConfig as any);
        set((state: ChatState) => ({ activeRequests: { ...state.activeRequests, [sessionId]: client } }));
        set({ currentGeneratingSessionId: sessionId });

        try {
          // 5. RAG & Search Setup (Re-run)
          const isNativeSearch = provider.type === 'gemini' || provider.type === 'google';
          let searchContext = '';
          let initialCitations: { title: string; url: string; source?: string }[] = [];

          const options = session.options || {};

          if (options.webSearch && !isNativeSearch) {
            try {
              const searchConfig = useApiStore.getState().googleSearchConfig;
              const { context, sources } = await performWebSearch(
                userMsg.content,
                searchConfig?.apiKey,
                searchConfig?.cx
              );
              searchContext = context;
              initialCitations = sources;
              // Initialize accumulatedCitations by pushing empty update with citations
              get().updateMessageContent(sessionId, messageId, '', undefined, undefined, initialCitations);
            } catch (e) {
              console.error('Regenerate search failed', e);
            }
          }

          // RAG Logic
          let ragContext = '';
          const sessionRagOptions = session.ragOptions || {};
          const finalRagOptions = {
            enableMemory: sessionRagOptions.enableMemory ?? true,
            enableDocs: sessionRagOptions.enableDocs ?? false,
            activeDocIds: sessionRagOptions.activeDocIds ?? [],
            activeFolderIds: sessionRagOptions.activeFolderIds ?? [],
            isGlobal: sessionRagOptions.isGlobal ?? false,
          };

          let ragReferences: RagReference[] = [];
          let ragUsage: { ragSystem: number; isEstimated: boolean } | undefined;

          if (finalRagOptions.enableMemory || finalRagOptions.enableDocs) {
            try {
              // Set loading state if needed
              const isSuperAssistant = sessionId === 'super_assistant';
              const effectiveRagOptions = {
                ...finalRagOptions,
                isGlobal: isSuperAssistant ? true : finalRagOptions.isGlobal,
                enableDocs: isSuperAssistant ? true : finalRagOptions.enableDocs,
                enableMemory: isSuperAssistant ? true : finalRagOptions.enableMemory,
                ragConfig: agent.ragConfig,
                onProgress: (stage: string, percentage: number) => {
                  // Since updateMessageProgress is not exposed, we just update generic status or skip
                  // get().updateMessageContent(sessionId, messageId, '', undefined, undefined, undefined, [], true);
                },
              };

              const { MemoryManager } = require('../lib/rag/memory-manager');
              const {
                context: retrievedContext,
                references,
                metadata,
                billingUsage,
              } = await MemoryManager.retrieveContext(
                userMsg.content,
                sessionId,
                effectiveRagOptions,
              );

              if (billingUsage) ragUsage = billingUsage;
              ragContext = retrievedContext;
              ragReferences = references;

            } catch (e) {
              console.error("RAG regenerate failed", e);
            }
          }


          // 6. Context Construction
          const activeWindowSize = agent.ragConfig?.contextWindow || 10;
          let finalSystemPrompt = agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');
          if (ragContext) finalSystemPrompt += `\n\n${ragContext}`;

          let contextMsgs: any[] = [];
          if (searchContext) {
            contextMsgs.push({ role: 'system', content: finalSystemPrompt + '\n\n' + searchContext });
          } else {
            contextMsgs.push({ role: 'system', content: finalSystemPrompt });
          }

          // History: messages before userMsg
          const formatContent = async (mContent: string, mImages?: any[]) => {
            const hasVision = modelConfig?.capabilities.vision;
            if (!mImages || mImages.length === 0 || !hasVision) return mContent;
            const parts: any[] = [{ type: 'text', text: mContent }];
            const { FileSystem } = require('expo-file-system/legacy');
            for (const img of mImages) {
              try {
                const base64 = await FileSystem.readAsStringAsync(img.original, { encoding: 'base64' });
                parts.push({ type: 'image_url', image_url: { url: `data:${img.mime || 'image/jpeg'};base64,${base64}` } });
              } catch (e) { }
            }
            return parts;
          };

          const historyMsgs = session.messages.slice(0, msgIndex);
          const history = await Promise.all(
            historyMsgs.slice(-activeWindowSize).map(async (m) => ({
              role: m.role,
              content: await formatContent(m.content, m.images),
            }))
          );

          contextMsgs = [...contextMsgs, ...history] as any;

          // 7. Stream
          let accumulatedContent = '';
          let accumulatedReasoning = '';
          let accumulatedCitations = initialCitations;
          let accumulatedUsage: any;

          let updateTimer: NodeJS.Timeout | null = null;
          const scheduleUpdate = () => {
            if (updateTimer) return;
            updateTimer = setTimeout(() => {
              updateTimer = null;
              const inputTokens = accumulatedUsage?.input || 0;
              const completionTokens = accumulatedUsage?.output || 0;
              get().updateMessageContent(
                sessionId,
                messageId,
                accumulatedContent,
                { input: inputTokens, output: completionTokens, total: inputTokens + completionTokens },
                accumulatedReasoning,
                accumulatedCitations,
                ragReferences
              );
            }, 100);
          };

          await client.streamChat(
            ContextManager.trimContext(contextMsgs, activeWindowSize),
            (chunk) => {
              if (typeof chunk === 'string') {
                accumulatedContent += chunk;
              } else {
                if (chunk.content) accumulatedContent += chunk.content;
                if (chunk.reasoning) accumulatedReasoning += chunk.reasoning;
                if (chunk.citations) accumulatedCitations = chunk.citations;
                if (chunk.usage) accumulatedUsage = chunk.usage;
              }
              scheduleUpdate();
            },
            (error) => console.warn('Regenerate error', error),
            options
          );

          // Final update
          const ragSystemTokens = ragUsage?.ragSystem || 0;
          const finalInput = (accumulatedUsage?.input || 0) + ragSystemTokens;
          const finalOutput = accumulatedUsage?.output || 0;

          get().updateMessageContent(
            sessionId,
            messageId,
            accumulatedContent,
            {
              input: finalInput,
              output: finalOutput,
              total: finalInput + finalOutput,
            },
            accumulatedReasoning,
            accumulatedCitations,
            ragReferences,
          );

        } finally {
          // Cleanup active request and ensure loading flags are cleared
          set((state: ChatState) => {
            const newRequests = { ...state.activeRequests };
            delete newRequests[sessionId];

            // Clear loading flags on the message to avoid stuck animations
            const updatedSessions = state.sessions.map((s) => {
              if (s.id === sessionId) {
                return {
                  ...s,
                  messages: s.messages.map((m) =>
                    m.id === messageId
                      ? { ...m, ragReferencesLoading: false, status: m.status === 'streaming' ? 'sent' : m.status }
                      : m
                  ),
                };
              }
              return s;
            });

            return {
              activeRequests: newRequests,
              sessions: updatedSessions,
              currentGeneratingSessionId: state.currentGeneratingSessionId === sessionId ? null : state.currentGeneratingSessionId,
            };
          });
        }
      },

      summarizeSession: async (sessionId: string) => {
        const session = get().getSession(sessionId);
        if (!session) return;
        const agentStore = useAgentStore.getState();
        const agent = agentStore.getAgent(session.agentId);
        if (!agent) return;

        try {
          await ContextManager.checkAndSummarize(sessionId, session.messages, agent);
          console.log('[ChatStore] Session summary triggered manually');
        } catch (e) {
          console.error('[ChatStore] Failed to summarize session:', e);
          throw e;
        }
      },
    }),
    {
      name: 'chat-storage',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({ sessions: state.sessions }),
    }
  )
);
