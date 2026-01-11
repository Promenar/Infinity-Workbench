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
    (set, get) => ({
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
            const toolsDesc = availableSkills.map(s => `- ${s.name} (${s.id}): ${s.description}`).join('\n');
            const toolInstruction = `\n\n[AVAILABLE TOOLS]\nYou have access to the following tools. USE THEM DIRECTLY via the native function calling mechanism. DO NOT output JSON in your response.\n${toolsDesc}\n\n[AUTO-EXECUTION RULES]\n1. ALWAYS explain your reasoning (in 1 short sentence) BEFORE calling a tool. This is crucial for the user to understand your plan.\n2. Do not ask the user for permission, just use the tool if needed.\n3. If the task requires multiple steps (e.g. search -> generate), YOU MUST CHAIN TOOL CALLS.\n4. After receiving a tool result, CHECK if it contains the necessary info. If YES, proceed immediately to the next step.\n5. DO NOT repeatedly search for the same information.`;
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

          // Add user message to context
          contextMsgs.push({ role: 'user', content: await formatContent(apiMessage.content, apiMessage.images) });

          // =====================================================================================
          // Phase 4: Agentic Loop Implementation
          // =====================================================================================


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

          const runAgentLoop = async () => {
            while (loopCount < MAX_LOOP_COUNT) {
              loopCount++;
              console.log(`[AgentLoop] Turn ${loopCount}/${MAX_LOOP_COUNT}`);

              // 4. Get Enabled Skills
              const availableSkills = skillRegistry.getEnabledSkills();
              console.log('[AgentLoop] Available Skills:', availableSkills.map(s => s.id));


              // 5. Stream Chat
              let toolCalls: ToolCall[] | undefined;
              let turnContent = '';
              let reasoningFromThisTurn = '';

              await client.streamChat(
                currentMessages as any,
                (token) => {
                  // Check for abort
                  const currentState = get();
                  if (!currentState.activeRequests[sessionId]) return;

                  // 5.0 Capture Usage
                  if (token.usage) {
                    accumulatedUsage = token.usage;
                  }

                  // 5.1 Handle Content
                  if (token.content) {
                    turnContent += token.content;
                    accumulatedContent += token.content;
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      token.usage
                    );
                  }

                  // 5.2 Handle Reasoning
                  if (token.reasoning) {
                    reasoningFromThisTurn += token.reasoning;
                    // Note: We don't add to accumulatedReasoning here to keep bubble clean
                    // Instead, we stream directly to the timeline
                    const stepId = `think_turn_${loopCount}`;
                    updateSteps({
                      id: stepId,
                      type: 'thinking',
                      content: reasoningFromThisTurn,
                      timestamp: Date.now()
                    });

                    // 🧹 Keep bubble reasoning empty if it's in timeline
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      token.usage,
                      '' // Clear bubble reasoning
                    );
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

              // 6. Output Processing

              // Fallback: Check if model outputted tool calls as JSON in text (DeepSeek/Legacy/ReAct behavior)
              if (!toolCalls || toolCalls.length === 0) {
                console.log('[AgentLoop] No native tool calls. Checking content fallback. Content len:', turnContent.length);

                // Strategy 1: Markdown Code Blocks
                // Match ``` followed by any generic language tag (or none), content, then ```
                const jsonBlockRegex = /```[\w-]*\s*([\s\S]*?)\s*```/yi;
                const match = turnContent.match(jsonBlockRegex);

                let potentialJson = '';
                if (match && match[1]) {
                  console.log('[AgentLoop] Regex matched code block.');
                  potentialJson = match[1].trim();
                } else if (turnContent.trim().startsWith('{') && turnContent.trim().endsWith('}')) {
                  // Strategy 2: Raw JSON (no markdown)
                  console.log('[AgentLoop] Content looks like raw JSON.');
                  potentialJson = turnContent.trim();
                }

                if (potentialJson) {
                  try {
                    // Sanitize: sometimes models put comments // in JSON
                    // We can't easily strip them without a library, but let's hope for standard JSON
                    const jsonContent = JSON.parse(potentialJson);
                    console.log('[AgentLoop] JSON Parsed successfully:', Object.keys(jsonContent));

                    // ... (Existing parsing logic) ...

                    // Avoid parsing non-object JSON (like simple code snippets)
                    if (potentialJson.startsWith('{')) {
                      const jsonContent = JSON.parse(potentialJson);

                      // Case A: Standard tool_calls wrapper
                      if (jsonContent.tool_calls) {
                        console.log('[AgentLoop] Detected tool calls in content (Standard JSON)');
                        toolCalls = jsonContent.tool_calls.map((tc: any) => ({
                          id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
                          name: tc.function.name,
                          arguments: typeof tc.function.arguments === 'string' ? JSON.parse(tc.function.arguments) : tc.function.arguments
                        }));
                      }
                      // Case B: Single function/tool wrapper
                      else if (jsonContent.function || jsonContent.tool) {
                        const fn = jsonContent.function || jsonContent.tool;

                        // Handle flat format: { function: "name", parameters: {...} }
                        if (typeof fn === 'string') {
                          console.log('[AgentLoop] Detected single function call in content (Flat Format)');
                          toolCalls = [{
                            id: `call_${Date.now()}`,
                            name: fn,
                            arguments: jsonContent.parameters || jsonContent.arguments || {}
                          }];
                          if (typeof toolCalls[0].arguments === 'string') {
                            try { toolCalls[0].arguments = JSON.parse(toolCalls[0].arguments); } catch { }
                          }
                        }
                        // Handle nested format: { function: { name: "..." } }
                        else if (fn.name) {
                          console.log('[AgentLoop] Detected single function call in content (Wrapper)');
                          toolCalls = [{
                            id: `call_${Date.now()}`,
                            name: fn.name,
                            arguments: typeof fn.arguments === 'string' ? JSON.parse(fn.arguments) : fn.arguments
                          }];
                        }
                      }
                      // Case C: ReAct / Action style (Gemini/LangChain style hallucinations)
                      else if (jsonContent.action && jsonContent.action !== 'Final Answer') {
                        console.log('[AgentLoop] Detected action/action_input in content (ReAct)');
                        toolCalls = [{
                          id: `call_${Date.now()}`,
                          name: jsonContent.action,
                          arguments: typeof jsonContent.action_input === 'string' ? JSON.parse(jsonContent.action_input) : jsonContent.action_input
                        }];
                      }

                      // Success: Clean up turnContent
                      if (toolCalls && toolCalls.length > 0) {
                        // If we extracted tool calls, remove the JSON block from the visible content
                        if (potentialJson === turnContent.trim()) {
                          turnContent = ''; // It was just JSON
                        } else if (match && match[0]) {
                          // It was a code block, remove it
                          turnContent = turnContent.replace(match[0], '').trim();
                        } else {
                          // Fallback: remove the raw string if we can find it
                          turnContent = turnContent.replace(potentialJson, '').trim();
                        }

                        // IMPORTANT: Update the UI to remove the JSON script
                        accumulatedContent = turnContent;
                        get().updateMessageContent(
                          sessionId,
                          currentAssistantMsgId,
                          accumulatedContent, // Cleaned content
                          accumulatedUsage
                        );
                      }
                    }
                  } catch (e) {
                    console.warn('[AgentLoop] Failed to parse fallback tool calls from content', e);
                  }
                }

                // Strategy 3: Special text pattern "call:function_name(arg1=val1, ...)"
                // Seen in Gemini hallucinations
                if (!toolCalls || toolCalls.length === 0) {
                  const callPattern = /call:([\w_]+)\(([\s\S]*?)\)/gi;
                  let callMatch;
                  const extractedCalls: ToolCall[] = [];

                  while ((callMatch = callPattern.exec(turnContent)) !== null) {
                    const funcName = callMatch[1];
                    const argsStr = callMatch[2];
                    console.log(`[AgentLoop] Detected text-based call: ${funcName}(${argsStr})`);

                    // Try parsing simple key-value arguments: query="xxx", count=5
                    const args: Record<string, any> = {};
                    const argPattern = /([\w_]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\d+(?:\.\d+)?)|(true|false|null))/gi;
                    let argMatch;
                    while ((argMatch = argPattern.exec(argsStr)) !== null) {
                      const key = argMatch[1];
                      const val = argMatch[2] || argMatch[3] || argMatch[4] || argMatch[5];
                      let finalVal: any = val;
                      if (argMatch[4]) finalVal = Number(val);
                      if (argMatch[5]) {
                        if (val === 'true') finalVal = true;
                        if (val === 'false') finalVal = false;
                        if (val === 'null') finalVal = null;
                      }
                      args[key] = finalVal;
                    }

                    // Fallback: If no structured args found, treat the whole string as "query"
                    if (Object.keys(args).length === 0 && argsStr.trim()) {
                      // Check if it looks like a single string literal
                      const quoteMatch = argsStr.trim().match(/^["'](.*)["']$/);
                      if (quoteMatch) {
                        args['query'] = quoteMatch[1];
                      } else {
                        args['query'] = argsStr.trim();
                      }
                    }

                    extractedCalls.push({
                      id: `txt_call_${Date.now()}_${extractedCalls.length}`,
                      name: funcName,
                      arguments: args
                    });

                    // Success cleanup: remove the call string from content
                    turnContent = turnContent.replace(callMatch[0], '').trim();
                  }

                  if (extractedCalls.length > 0) {
                    toolCalls = extractedCalls;
                    // IMPORTANT: Update UI to remove the text calls
                    accumulatedContent = turnContent;
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      accumulatedUsage
                    );
                  }
                }
              }

              // 🌟 Final Turn Reasoning Processing:
              // Real-time logic in stream loop already moved reasoning to timeline.
              // Just ensure we don't have residual reasoning in bubble metadata.
              if (reasoningFromThisTurn) {
                accumulatedReasoning = '';
                get().updateMessageContent(
                  sessionId,
                  currentAssistantMsgId,
                  accumulatedContent,
                  accumulatedUsage,
                  ''
                );
              }

              if (toolCalls && toolCalls.length > 0) {
                console.log(`[AgentLoop] Processing ${toolCalls.length} tool calls. Detailed:`, JSON.stringify(toolCalls));

                // Final Turn Content Cleanup: If we have tool calls, the text accompanies the plan.
                // We move the turn text to the timeline too if it looks like a plan.
                if (turnContent && turnContent.length > 0) {
                  updateSteps({
                    id: `plan_${Date.now()}`,
                    type: 'thinking',
                    content: `Plan: ${turnContent}`,
                    timestamp: Date.now()
                  });

                  // Clear turn content from main window if it's just a precursor to tool calls
                  accumulatedContent = accumulatedContent.replace(turnContent, '').trim();
                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    accumulatedUsage,
                    ''
                  );
                }

                // Add Assistant Message (with ToolCalls) to History
                currentMessages.push({
                  role: 'assistant',
                  content: turnContent || '',
                  reasoning: reasoningFromThisTurn, // 🧠 Critical: Persist thoughts for reasoning models
                  tool_calls: toolCalls.map(tc => ({
                    id: tc.id,
                    type: 'function',
                    function: { name: tc.name, arguments: JSON.stringify(tc.arguments) }
                  }))
                } as any);

                // Execute Tools
                for (const tc of toolCalls) {
                  const skill = skillRegistry.getSkill(tc.name);
                  const stepId = `step_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;

                  // 1. Add Timeline: Calling
                  updateSteps({
                    id: stepId,
                    type: 'tool_call',
                    toolName: tc.name,
                    toolArgs: tc.arguments,
                    timestamp: Date.now()
                  });

                  let result: ToolResult;
                  try {
                    if (skill) {
                      const context: SkillContext = { sessionId, agentId: agent.id };
                      result = await skill.execute(tc.arguments, context);
                    } else {
                      result = { id: tc.id, content: `Error: Skill ${tc.name} not found`, status: 'error' };
                    }
                  } catch (e: any) {
                    result = { id: tc.id, content: `Error executing ${tc.name}: ${e.message}`, status: 'error' };
                  }

                  // 2. Add Timeline: Result
                  updateSteps({
                    id: `res_${stepId}`,
                    type: result.status === 'success' ? 'tool_result' : 'error',
                    toolName: tc.name,
                    content: result.content,
                    timestamp: Date.now()
                  });

                  // 3. Add Tool Message to History
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
                break;
              }
            }
          };

          await runAgentLoop();

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
            const contentMessages = currentMessages.filter((m) => m.role !== 'system');
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
          const latestMsg = get().getSession(sessionId)?.messages.find(m => m.id === assistantMsgId);
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
          set((state) => {
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
          set((state) => ({
            sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, messages } : s)),
          }));
        }
      },

      generateSessionTitle: async (sessionId) => {
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
            .map((m) => `${m.role}: ${m.content}`)
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

      vectorizeMessage: async (sessionId, messageId) => {
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

      regenerateMessage: async (sessionId, messageId) => {
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
        set((state) => ({
          sessions: state.sessions.map((s) => {
            if (s.id === sessionId) {
              const truncatedMessages = s.messages.slice(0, msgIndex + 1);
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
        set((state) => ({ activeRequests: { ...state.activeRequests, [sessionId]: client } }));
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
          set((state) => {
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

      summarizeSession: async (sessionId) => {
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
      partialize: (state) => ({ sessions: state.sessions }), // Don't persist activeRequests
    },
  ),
);
