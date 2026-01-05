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
import { createLlmClient } from '../lib/llm/factory';
import { estimateTokens } from '../features/chat/utils/token-counter';
import { performWebSearch } from '../features/chat/utils/web-search';
import { LlmClient } from '../lib/llm/types';
import * as FileSystem from 'expo-file-system/legacy';
import { MemoryManager } from '../lib/rag/memory-manager';
import { graphExtractor } from '../lib/rag/graph-extractor'; // ✅ Import KG Extractor

import { ContextManager } from '../features/chat/utils/ContextManager';

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
    return messages.map((msg) => ({
      ...msg,
      isArchived: archivedMessageIds.has(msg.id),
    }));
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
  setMessagesArchived: (sessionId: SessionId, messageIds: string[]) => void;
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

      setMessagesArchived: (sessionId, messageIds) =>
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
                    idSet.has(m.id) ? { ...m, isArchived: true } : m,
                  ),
                  // 🔑 关键修复：保留 stats.billing，防止被覆盖
                  stats: s.stats, // 保持原有统计数据不变
                };
              }
              return s;
            }),
          };
        }),

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
            temperature: agent.params?.temperature ?? 0.7,
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
          // 将 RAG 上下文注入到系统提示词中
          let finalSystemPrompt =
            agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');
          if (ragContext) {
            finalSystemPrompt += `\n\n${ragContext}`;
          }

          let contextMsgs = [];
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
            isHistory?: boolean,
          ) => {
            // Priority 1: If model doesn't support vision, skip image processing entirely
            const hasVision = modelConfig?.capabilities.vision;
            if (!images || images.length === 0 || !hasVision) return msgContent;

            // Priority 2: In history, we might want to skip or limit high-res images to save bandwidth/lag
            // But for now, just skip if it's the current model is not vision-capable
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

          // 🔑 动态上下文窗口大小
          const activeWindowSize = agent.ragConfig?.contextWindow || 10;

          const history = await Promise.all(
            session.messages.slice(-activeWindowSize).map(async (m: Message) => ({
              role: m.role,
              content: await formatContent(m.content, m.images, true),
            })),
          );

          contextMsgs = [
            ...contextMsgs,
            ...history,
            { role: 'user', content: await formatContent(content, normalizedImages) },
          ] as any;

          const context = ContextManager.trimContext(contextMsgs, activeWindowSize);
          // For token estimation, we only count text parts
          const contextText = context
            .map((m: any) => {
              if (typeof m.content === 'string') return m.content;
              return (m.content as any[])
                .map((p: any) => (p.type === 'text' ? p.text : ''))
                .join('\n');
            })
            .join('\n');
          const totalContextTokens = estimateTokens(contextText);

          // 5. Stream Chat with optimized batching
          let updateTimer: NodeJS.Timeout | null = null;
          let lastTokenEstimateLength = 0;
          let accumulatedUsage: { input: number; output: number; total: number } | undefined;

          const scheduleUpdate = () => {
            if (updateTimer) return; // 防止重复调度
            updateTimer = setTimeout(() => {
              updateTimer = null;

              let inputTokens = totalContextTokens;
              let completionTokens = 0;

              if (accumulatedUsage) {
                // Prefer API usage if available
                inputTokens = accumulatedUsage.input;
                completionTokens = accumulatedUsage.output;
              } else {
                // Fallback to estimation
                const shouldEstimateTokens =
                  accumulatedContent.length - lastTokenEstimateLength > 500;
                completionTokens = shouldEstimateTokens
                  ? estimateTokens(accumulatedContent)
                  : Math.floor(accumulatedContent.length / 4);

                if (shouldEstimateTokens) {
                  lastTokenEstimateLength = accumulatedContent.length;
                }
              }

              get().updateMessageContent(
                sessionId,
                assistantMsgId,
                accumulatedContent,
                {
                  input: inputTokens,
                  output: completionTokens,
                  total: inputTokens + completionTokens,
                },
                accumulatedReasoning,
                accumulatedCitations,
                ragReferences,
              ); // ✅ 传入 ragReferences 确保 RAG 检测
            }, 100); // 每 100ms 最多更新一次，减少渲染压力
          };

          await client.streamChat(
            context,
            (chunk) => {
              // Handle object chunk (new) or string chunk (legacy safety)
              if (typeof chunk === 'string') {
                accumulatedContent += chunk;
              } else {
                if (chunk.content) accumulatedContent += chunk.content;
                if (chunk.reasoning) accumulatedReasoning += chunk.reasoning;
                if (chunk.citations) accumulatedCitations = chunk.citations; // Native citations (replace/update)
                if (chunk.usage) accumulatedUsage = chunk.usage;
              }
              scheduleUpdate();
            },
            (error) => {
              console.warn('[ChatStore] Stream error:', error);
            },
            options, // Pass options including webSearch
          );

          // Final Update
          let finalInputTokens = totalContextTokens;
          let finalCompletionTokens = estimateTokens(accumulatedContent);

          if (accumulatedUsage) {
            finalInputTokens = accumulatedUsage.input;
            finalCompletionTokens = accumulatedUsage.output;
          }

          // 🔑 关键修复: 累加 RAG 检索成本（Query Rewrite + Embedding）
          const ragSystemTokens = ragUsage?.ragSystem || 0;

          get().updateMessageContent(
            sessionId,
            assistantMsgId,
            accumulatedContent,
            {
              input: finalInputTokens + ragSystemTokens, // ✅ 将 RAG 成本计入输入
              output: finalCompletionTokens,
              total: finalInputTokens + finalCompletionTokens + ragSystemTokens,
            },
            accumulatedReasoning,
            accumulatedCitations,
            ragReferences,
          );

          // 🔑 关键修复1: 归档本轮对话到向量记忆
          // ✅ 仅当enableMemory开启时才归档
          if (finalRagOptions.enableMemory !== false) {
            try {
              const archiveStartTime = Date.now();

              // 🎯 启动归档状态
              const { updateProcessingState } = await import('../store/rag-store').then((m) =>
                m.useRagStore.getState(),
              );
              updateProcessingState(
                {
                  sessionId,
                  status: 'chunking',
                  startTime: archiveStartTime,
                  chunks: [],
                },
                assistantMsgId,
              );

              // ✅ 关键修复：让React先渲染loading状态
              await new Promise((resolve) => setTimeout(resolve, 0));

              await MemoryManager.addTurnToMemory(
                sessionId,
                content, // userMsg.content
                accumulatedContent, // assistantMsg.content
                userMsg.id,
                assistantMsgId,
              );

              // ✅ 确保loading状态至少显示800ms
              const elapsed = Date.now() - archiveStartTime;
              if (elapsed < 800) {
                await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));
              }

              // 🎯 归档完成（添加切片数量信息）
              const estimatedChunks = Math.ceil((content.length + accumulatedContent.length) / 500);
              updateProcessingState(
                {
                  sessionId,
                  status: 'archived',
                  chunks: [],
                },
                assistantMsgId,
              );

              // ✅ 关键修复3：更新Store中的消息状态，通知UI显示绿勾
              get().setMessagesArchived(sessionId, [userMsg.id, assistantMsgId]);

              console.log('[RAG] 对话已归档到向量库');
            } catch (e) {
              console.error('[RAG] 记忆归档失败:', e);
              // 错误状态
              const { updateProcessingState } = await import('../store/rag-store').then((m) =>
                m.useRagStore.getState(),
              );
              updateProcessingState(
                {
                  sessionId,
                  status: 'error',
                },
                assistantMsgId,
              );
            }
          }

          // 🔑 关键修复: 触发 AI 回复的知识图谱实体提取 (KG Extraction)
          // 逻辑修正：不再依赖 enableDocs，而是独立的 enableKnowledgeGraph 开关
          if (accumulatedContent.trim()) {
            setTimeout(async () => {
              try {
                const { useSettingsStore } = require('../store/settings-store');
                const globalConfig = useSettingsStore.getState().globalRagConfig;
                const session = get().getSession(sessionId);
                if (!session) return;

                // 1. 计算是否启用
                // 优先级: Session Override > Global Setting
                // 特例: Super Assistant 总是启用 (视为全域知识维护者)
                const isSuperAssistant = sessionId === 'super_assistant';
                const sessionKgOption = session.ragOptions?.enableKnowledgeGraph;
                const isKgEnabled =
                  isSuperAssistant ||
                  (sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph);

                if (!isKgEnabled) return;

                // 2. 检查提取策略 (Cost Strategy)
                // 'on-demand': 仅手动触发 (MessageContextMenu)
                // 'summary-first' / 'full': 自动触发 (对于会话流，summary-first等同于full，以免丢失细节)
                const costStrategy =
                  agent?.ragConfig?.costStrategy || globalConfig.costStrategy || 'summary-first';

                if (costStrategy === 'on-demand' && !isSuperAssistant) {
                  // 超级助手即使在按需模式下，也建议自动提取以维持全域助手智商？
                  // 或者尊重用户设置？用户说是“按需”，那可能真的想按需。
                  // 但用户之前的反馈暗示希望能自动提取。
                  // 为了安全起见，若显式设为 on-demand 则跳过，由用户手动点。
                  return;
                }

                // Indicate start of extraction
                get().setKGExtractionStatus(sessionId, true);

                await graphExtractor.extractAndSave(accumulatedContent, undefined, {
                  sessionId,
                  agentId: session.agentId,
                });
              } catch (e) {
                console.warn('[ChatStore] AI Response KG extraction failed:', e);
              } finally {
                // Indicate end of extraction
                get().setKGExtractionStatus(sessionId, false);
              }
            }, 500); // 延迟一点执行，避免与UI渲染抢占
          }

          // 🔑 关键修复2: 检查并触发自动摘要
          try {
            const currentMessages = get().getSession(sessionId)?.messages || [];

            // ✅ 修复：只计算非system消息
            const contentMessages = currentMessages.filter((m) => m.role !== 'system');
            const contentMessageCount = contentMessages.length;

            // ✅ 关键修复：查询所有摘要覆盖的消息ID范围
            const summariesResult = await db.execute(
              'SELECT start_message_id, end_message_id FROM context_summaries WHERE session_id = ?',
              [sessionId],
            );

            // 构建已被摘要覆盖的消息ID集合
            const summarizedMessageIds = new Set<string>();
            if (summariesResult.rows) {
              const rows =
                (summariesResult.rows as any)._array || (summariesResult.rows as any) || [];

              for (const row of rows) {
                if (row.start_message_id && row.end_message_id) {
                  // 找到起始和结束消息的索引
                  const startIdx = contentMessages.findIndex((m) => m.id === row.start_message_id);
                  const endIdx = contentMessages.findIndex((m) => m.id === row.end_message_id);

                  // 将这个范围内的所有消息ID加入集合
                  if (startIdx !== -1 && endIdx !== -1) {
                    for (let i = startIdx; i <= endIdx; i++) {
                      summarizedMessageIds.add(contentMessages[i].id);
                    }
                  }
                }
              }
            }

            // 计算未被摘要的消息数
            const newMessagesCount = contentMessages.filter(
              (m) => !summarizedMessageIds.has(m.id),
            ).length;

            // 活跃窗口大小（对应ContextManager的finalConfig.maxMessages）
            const activeWindowSize = agent.ragConfig?.contextWindow || 10;
            const summaryThreshold = agent.ragConfig?.summaryThreshold || 20;

            // ✅ 关键修复：只有当new消息数 > (活跃窗口 + 阈值) 时才触发
            // 因为ContextManager会保留最后activeWindowSize条消息，只摘要超出部分
            const needsSummary = newMessagesCount > activeWindowSize + summaryThreshold;

            console.log(
              `[ChatStore] 摘要检查: total=${contentMessageCount}, summarized=${summarizedMessageIds.size}, new=${newMessagesCount}, activeWindow=${activeWindowSize}, threshold=${summaryThreshold}, needsSummary=${needsSummary}`,
            );
            if (needsSummary) {
              const summaryStartTime = Date.now();

              // 🎯 启动摘要状态
              const { updateProcessingState } = await import('../store/rag-store').then((m) =>
                m.useRagStore.getState(),
              );
              updateProcessingState(
                {
                  sessionId,
                  status: 'summarizing',
                  startTime: summaryStartTime,
                },
                assistantMsgId,
              );

              // ✅ 关键修复：让React先渲染loading状态
              await new Promise((resolve) => setTimeout(resolve, 0));
              // 查询摘要前的数量
              const beforeCount = await db.execute(
                'SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?',
                [sessionId],
              );
              const beforeSummaryCount =
                (beforeCount.rows as any)?._array?.[0]?.count ||
                (beforeCount.rows as any)?.[0]?.count ||
                ((beforeCount.rows as any)._array
                  ? (beforeCount.rows as any)._array[0]?.count
                  : (beforeCount.rows as any)[0]?.count) ||
                0;

              await ContextManager.checkAndSummarize(sessionId, currentMessages, agent);

              // ✅ 关键修复：确保loading状态至少显示800ms
              const elapsed = Date.now() - summaryStartTime;
              if (elapsed < 800) {
                await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));
              }

              // 查询摘要后的数量
              const afterCount = await db.execute(
                'SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?',
                [sessionId],
              );
              const afterSummaryCount =
                (afterCount.rows as any)?._array?.[0]?.count ||
                (afterCount.rows as any)?.[0]?.count ||
                ((afterCount.rows as any)._array
                  ? (afterCount.rows as any)._array[0]?.count
                  : (afterCount.rows as any)[0]?.count) ||
                0;

              console.log(
                `[ChatStore] 摘要前后对比: before=${beforeSummaryCount}, after=${afterSummaryCount}`,
              );

              // 🎯 只有在真正生成了新摘要时才标记为summarized
              if (afterSummaryCount > beforeSummaryCount) {
                updateProcessingState(
                  {
                    sessionId,
                    status: 'summarized',
                    summary: '',
                  },
                  assistantMsgId,
                );
                console.log('[ChatStore] ✅ 摘要已生成');
              } else {
                updateProcessingState(
                  {
                    sessionId,
                    status: 'idle',
                  },
                  assistantMsgId,
                );
                console.log('[ChatStore] ℹ️ 未达到摘要条件，跳过');
              }
            }

            console.log('[RAG] 摘要检查完成');
          } catch (e) {
            console.error('[RAG] 摘要生成失败:', e);
          }

          // Update Session Stats & Title
          const sessionAllText = (get().getSession(sessionId)?.messages || [])
            .map((m) => m.content)
            .join('\n');

          // Prepare Billing Usage
          const billingUsage = {
            chatInput: {
              count: accumulatedUsage ? accumulatedUsage.input : totalContextTokens,
              isEstimated: !accumulatedUsage,
            },
            chatOutput: {
              count: accumulatedUsage
                ? accumulatedUsage.output
                : estimateTokens(accumulatedContent),
              isEstimated: !accumulatedUsage,
            },
            ragSystem: ragUsage
              ? { count: ragUsage.ragSystem, isEstimated: ragUsage.isEstimated }
              : { count: 0, isEstimated: false },
            total:
              (accumulatedUsage
                ? accumulatedUsage.total
                : totalContextTokens + estimateTokens(accumulatedContent)) +
              (ragUsage?.ragSystem || 0),
          };

          get().updateSession(sessionId, {
            stats: {
              totalTokens: billingUsage.total,
              billing: billingUsage,
            },
          });

          // Track Global Stats
          try {
            const { useTokenStatsStore } = await import('./token-stats-store');
            useTokenStatsStore.getState().trackUsage({
              modelId: modelId,
              usage: {
                chatInput: billingUsage.chatInput,
                chatOutput: billingUsage.chatOutput,
                ragSystem: billingUsage.ragSystem,
              },
            });
          } catch (e) {
            console.warn('[ChatStore] Failed to track global stats:', e);
          }

          if (
            session.messages.length <= 1 ||
            session.title === agent.name ||
            session.title === 'New Conversation'
          ) {
            get().updateSessionTitle(
              sessionId,
              content.substring(0, 30) + (content.length > 30 ? '...' : ''),
            );
          }
        } catch (error) {
          if ((error as any).name === 'AbortError' || (error as Error).message.includes('abort')) {
            console.log('Stream aborted');
          } else {
            console.warn('Chat error:', error);
            // Friendly localized error message
            const errorMsg =
              '抱歉，遇到网络问题或认证失败。请检查您的网络连接、API Key是否正确，或稍后重试。';
            get().updateMessageContent(
              sessionId,
              assistantMsgId,
              accumulatedContent + `\n\n⚠️ ${errorMsg}\n\n(Error: ${(error as Error).message})`,
            );
          }
        } finally {
          set((state) => {
            const newRequests = { ...state.activeRequests };
            delete newRequests[sessionId];
            return { activeRequests: newRequests, currentGeneratingSessionId: null };
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
          const { MemoryManager } = require('../lib/rag/memory-manager');
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

        set({ currentGeneratingSessionId: null });
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
