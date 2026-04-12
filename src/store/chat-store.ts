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
  TaskState, // ✅ Added
  ChatAttachment, // ✅ Added
  ToolResultArtifact, // 🆕 工具产物类型
  UpdateMessageOptions, // 🆕 updateMessageContent 选项参数
} from '../types/chat';
import { db } from '../lib/db';
import { useAgentStore } from './agent-store';
import { useApiStore } from './api-store';
import { useSettingsStore } from './settings-store';

import { useRagStore } from './rag-store'; // ✅ 导入 RagStore
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
import { StreamParser } from '../lib/llm/stream-parser'; // ✅ StreamParser

import { FormatterFactory } from '../lib/llm/formatter-factory';
import { inferModelFamily, getContinuationPrompt, getModelSpecificEnhancements } from '../lib/llm/model-prompts'; // 🆕 模型特定提示词
import { createMessageManager, createSessionManager, createApprovalManager, createToolExecutor } from './chat'; // ✅ Import Managers
import { performClientSideSearch, buildSystemPrompt } from './chat/context-builder'; // ✅ Phase 4b: Context Builder
import { archiveToRag, extractKnowledgeGraph, updateStats } from './chat/post-processor'; // ✅ Phase 4b: Post Processor

// =====================================================================================
// 🚨 ARCHITECTURE RED LINE (2026-01-20) 🚨
// =====================================================================================
//
// ⚠️ CRITICAL RULES FOR THIS FILE:
//
// 1. NO NEW NON-UI LOGIC: Do NOT add new business logic, algorithms, or complex data
//    processing to this file. It is already too large.
//
// 2. UI STATE ONLY: Modifications should be limited to UI state management (toggles,
//    simple setters, layout updates).
//
// 3. USE HOOKS: New features (e.g., Speech-T-Text, File Processing) MUST be implemented
//    as separate Hooks (e.g., useSpeech.ts) or Services, and imported into components.
//    Do NOT inline them here.
//
// 4. PRESERVE STABILITY: This file handles the critical hot-path of the application.
//    Refactors here are high-risk. Follow the "Boy Scout Rule" gently, but avoid
//    massive rewrites without strict approval.
//
// Refer to: .agent/PROJECT_RULES.md (Rule 16)
// =====================================================================================

// 🔑 高风险工具列表移至 Skill 定义 (isHighRisk) - Refactored 2026-01-20
// const HIGH_RISK_TOOLS = [...];


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

export interface ChatState {
  sessions: Session[];
  activeRequests: Record<string, LlmClient>; // sessionId -> activeClient
  activeKGExtractions: Record<string, boolean>; // sessionId -> isExtractingKG
  currentGeneratingSessionId: string | null;

  // 🔑 Phase 4b: 从 SQLite 加载会话
  loadSessions: () => Promise<void>;
  // ✅ Pagination: Load messages for a session (On-Demand)
  loadSessionMessages: (sessionId: SessionId, limit?: number, beforeTimestamp?: number) => Promise<void>;

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
      isResumption?: boolean; // ✅ Added for Steerable Loop
      skipUserMessage?: boolean; // ✅ 重新发送时跳过创建用户消息
      toolsEnabled?: boolean; // ✅ Override for tool usage
      isContinuationApproval?: boolean; // 🆕 续杯批准标志（用于生成模型特定的提示词）
      files?: ChatAttachment[]; // ✅ Added for file upload
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
      toolsEnabled?: boolean; // New option
      thinkingLevel?: 'low' | 'medium' | 'high' | 'minimal';
      strictMode?: boolean; // ✅ Enforce one-step-at-a-time task execution
      enableTimeInjection?: boolean; // ✅ Inject system time into context
    },
  ) => void;
  updateSessionScrollOffset: (id: SessionId, offset: number) => void;
  updateMessageContent: (
    sessionId: SessionId,
    messageId: string,
    content: string,
    options?: UpdateMessageOptions
  ) => void;
  updateMessageProgress: (sessionId: string, messageId: string, progress: RagProgress) => void;
  updateSessionInferenceParams: (id: SessionId, params: InferenceParams) => void;
  deleteMessage: (sessionId: SessionId, messageId: string) => Promise<void>;
  deleteMessagesAfter: (sessionId: SessionId, timestamp: number) => Promise<void>;

  toggleSessionPin: (sessionId: SessionId) => void;


  updateSessionDraft: (sessionId: SessionId, draft: string | undefined) => void;
  // Deprecated: setMessagesArchived: (sessionId: SessionId, messageIds: string[]) => void;
  setVectorizationStatus: (sessionId: SessionId, messageIds: string[], status: 'processing' | 'success' | 'error') => void;
  flushMessageUpdates: (sessionId: string, messageId: string) => void;
  updateMessageLayout: (sessionId: SessionId, messageId: string, height: number) => void;
  setKGExtractionStatus: (sessionId: SessionId, isExtracting: boolean) => void;
  vectorizeMessage: (sessionId: string, messageId: string) => Promise<void>;
  summarizeSession: (sessionId: string) => Promise<void>;
  regenerateMessage: (sessionId: string, messageId: string) => Promise<void>; // ✅ New Action
  dismissActiveTask: (sessionId: string) => void;

  // Steerable Agent Loop Actions
  setExecutionMode: (sessionId: string, mode: 'auto' | 'semi' | 'manual') => void;
  setLoopStatus: (sessionId: string, status: 'idle' | 'running' | 'paused' | 'waiting_for_approval' | 'completed') => void;
  setPendingIntervention: (sessionId: string, intervention: string | undefined) => void;
  setApprovalRequest: (sessionId: string, request: { type?: 'tool_approval' | 'continuation'; toolName: string; args: any; reason: string } | undefined) => void;
  // Internal Helper (exposed for loop and resume)
  executeTools: (sessionId: string, toolCalls: ToolCall[], targetMessageId?: string) => Promise<void>;
  resumeGeneration: (sessionId: string, approved?: boolean, intervention?: string) => Promise<void>;
  toggleMcpServer: (sessionId: string, serverId: string) => void;
  toggleSkill: (sessionId: string, skillId: string) => void;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get): ChatState => {
      const messageManager = createMessageManager({ get, set });
      const sessionManager = createSessionManager({ get, set });
      const approvalManager = createApprovalManager({ get, set });
      const toolExecutor = createToolExecutor({ get, set });

      return {

        sessions: [],
        activeRequests: {},
        activeKGExtractions: {},
        currentGeneratingSessionId: null,

        // 🔑 Phase 4b: 从 SQLite 加载会话 (Metadata Only to improve startup time)
        loadSessions: async () => {
          try {
            const { SessionRepository } = await import('../lib/db/session-repository');
            // ✅ CHANGE: Use getAllSessions() instead of getAllFullSessions()
            // This loads only the session metadata. Messages will be loaded on demand.
            const sessions = await SessionRepository.getAll();
            set({ sessions });
            console.log(`[ChatStore] Loaded ${sessions.length} sessions (metadata) from SQLite`);
          } catch (e) {
            console.error('[ChatStore] Failed to load sessions from SQLite:', e);
          }
        },

        loadSessionMessages: async (sessionId, limit = 5, beforeTimestamp) => {
          const { SessionRepository } = await import('../lib/db/session-repository');

          let newMessages: Message[] = [];
          if (beforeTimestamp) {
            // Load older messages (Pagination)
            newMessages = await SessionRepository.getMessagesBefore(sessionId, beforeTimestamp, limit);
          } else {
            // Initial Load (Latest)
            newMessages = await SessionRepository.getLatestMessages(sessionId, limit);
          }

          set((state) => ({
            sessions: state.sessions.map((s) => {
              if (s.id !== sessionId) return s;

              // If it's initial load (no cursor), strictly replace or merge?
              // Standard approach for "initial load" is replace if we assume we are starting fresh, 
              // BUT if we already have messages (e.g. from user sending), we should be careful.
              // Actually, getLatestMessages returns the *end* of the conversation.

              let updatedMessages: Message[];
              if (beforeTimestamp) {
                // Prepend older messages
                // Filter out duplicates just in case
                const existingIds = new Set(s.messages.map(m => m.id));
                const uniqueNew = newMessages.filter(m => !existingIds.has(m.id));
                updatedMessages = [...uniqueNew, ...s.messages];
              } else {
                // Initial load: logic is tricky.
                // If we have "pending" messages (sending), we must keep them.
                // But usually loadSessionMessages is called when entering the screen.
                // Let's assume we replace the *persisted* messages but keep any *local* state if we were robust,
                // but since this is basically "open chat", replacing is safe-ish, 
                // EXCEPT if we have optimistic updates.
                // For now, let's just use the loaded messages, as this is usually called on mount.
                updatedMessages = newMessages;
              }

              return {
                ...s,
                messages: updatedMessages,
                hasMore: newMessages.length >= limit, // If we got less than limit, no more history
              };
            }),
          }));
        },

        addSession: sessionManager.addSession,
        setKGExtractionStatus: (sessionId, isExtracting) =>
          set((state) => ({
            activeKGExtractions: { ...state.activeKGExtractions, [sessionId]: isExtracting },
          })),
        updateSession: sessionManager.updateSession,
        deleteSession: sessionManager.deleteSession,
        addMessage: messageManager.addMessage,
        getSessionsByAgent: sessionManager.getSessionsByAgent,
        getSession: sessionManager.getSession,

        toggleSessionPin: sessionManager.toggleSessionPin,

        updateSessionTitle: sessionManager.updateSessionTitle,
        updateSessionPrompt: sessionManager.updateSessionPrompt,
        updateSessionModel: sessionManager.updateSessionModel,
        updateSessionOptions: sessionManager.updateSessionOptions,
        updateSessionScrollOffset: sessionManager.updateSessionScrollOffset,
        updateMessageContent: (sessionId: string, messageId: string, content: string, options?: UpdateMessageOptions) => {
          messageManager.updateMessageContent(sessionId, messageId, content, options);
        },

        updateSessionInferenceParams: sessionManager.updateSessionInferenceParams,

        updateSessionDraft: sessionManager.updateSessionDraft,

        updateMessageProgress: messageManager.updateMessageProgress,
        flushMessageUpdates: messageManager.flushMessageUpdates,

        deleteMessage: messageManager.deleteMessage,
        deleteMessagesAfter: messageManager.deleteMessagesAfter,
        setVectorizationStatus: messageManager.setVectorizationStatus,


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

        dismissActiveTask: sessionManager.dismissActiveTask,

        setExecutionMode: approvalManager.setExecutionMode,

        setLoopStatus: approvalManager.setLoopStatus,

        setPendingIntervention: approvalManager.setPendingIntervention,

        setApprovalRequest: approvalManager.setApprovalRequest,

        executeTools: toolExecutor.executeTools,

        resumeGeneration: approvalManager.resumeGeneration,

        toggleMcpServer: sessionManager.toggleMcpServer,
        toggleSkill: sessionManager.toggleSkill,





        generateMessage: async (sessionId, content, options) => {
          const session = get().getSession(sessionId);
          if (!session) return;

          // 🛡️ Safety: If messages are empty (metadata only loaded), we MUST load latest context first?
          // Actually, if the user sends a message, we add it to `messages` array.
          // If `messages` is empty, it might mean it's a new session OR it's an old session not loaded.
          // If it's an old session not loaded, `messages` is empty array.
          // If we just add user message, `messages` becomes [UserMessage].
          // Then we generate reply. The context will include only [UserMessage].
          // WE LOSE CONTEXT!
          // 
          // FIX: Check if we need to load context.
          // But `generateMessage` is usually called from UI where messages are visible.
          // UI calls `loadSessionMessages` on mount.
          // So `session.messages` *should* be populated by the time user sends.
          // HOWEVER, to be safe, we could check if session has unread? No.
          // Let's assume UI handles loading.


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

          // 2.1 Process Files (Hybrid Strategy)
          // Strategy: Native Support vs Text Extraction
          // Regex-based capability check (Forward Compatible)
          const isNativeDocModel = /^(gemini-|claude-)/i.test(modelConfig.id);
          const processedFiles: ChatAttachment[] = [];
          let fileContext = '';

          if (options?.files && options.files.length > 0) {
            for (const file of options.files) {
              // Strategy A: Native Support (Gemini/Claude)
              if (isNativeDocModel) {
                // Pass directly to model (File URI will be handled by adapter)
                // We might need to read base64 here if adapter expects it, 
                // but let's assume ChatStore prepares the message object and adapter handles actual I/O or we do it here.
                // For now, valid 'files' in Message are enough for Native.
                // However, we need to ensure the adapter knows how to read `file.uri`.
                // For simplicity, we can read base64 here if needed, but let's stick to attaching metadata 
                // and letting a helper 'formatContent' handle the heavy lifting.
                processedFiles.push(file);
              }
              // Strategy B: Text Extraction (DeepSeek/OpenAI/Others)
              else {
                // If content is already extracted (by DocumentService), use it.
                // Otherwise (should not happen if we use DocumentService properly), extract it.
                // Assuming DocumentService fills 'content' field if possible, or we might need to extract here.
                // But `DocumentService.pickDocument` returns `ChatAttachment` which *doesn't* have content by default?
                // Wait, `ChatAttachment` interface has `content?: string`.
                // We should ensure it's populated.

                let text = file.content;
                if (!text) {
                  // Fallback: If no content, we can't do much for non-native models unless we read it now.
                  // Since we are in an async function, we could try to read it.
                  try {
                    const { documentService } = await import('../lib/file/document-service');
                    text = await documentService.extractText(file);
                  } catch (e) {
                    console.warn(`Failed to extract text from ${file.name}`, e);
                    text = `[Error reading file: ${file.name}]`;
                  }
                }

                if (text) {
                  fileContext += `\n\n--- File: ${file.name} ---\n${text}\n----------------\n`;
                }
              }
            }
          }

          // If we have extracted text, append to content
          let finalContent = content;
          if (fileContext) {
            finalContent += fileContext;
          }

          // 2. Add User Message
          const promptTokens = estimateTokens(finalContent);
          const normalizedImages: GeneratedImageData[] | undefined = options?.images?.map((img) => {
            if (typeof img === 'string') {
              return { thumbnail: img, original: img, mime: 'image/jpeg' };
            }
            return img;
          });

          const userMsg: Message = {
            id: `msg_${Date.now()}`,
            role: 'user',
            content: finalContent, // Use content with appended files
            createdAt: Date.now(),
            tokens: { input: promptTokens, output: 0, total: promptTokens },
            images: normalizedImages,
            files: processedFiles.length > 0 ? processedFiles : undefined, // Attach native files
          };

          // 重新发送/恢复时跳过创建用户消息
          if (!options?.isResumption && !options?.skipUserMessage) {
            get().addMessage(sessionId, userMsg);
          } else {
            console.log('[ChatStore] Skipping User Message creation (isResumption or skipUserMessage)');
          }

          set({ currentGeneratingSessionId: sessionId });

          // 3. Assistant Message Handling
          let assistantMsgId: string;
          let accumulatedContent = '';

          if (options?.isResumption) {
            // ✅ 虚拟拆分架构关键修复：向前查找最后一个assistant消息
            // executeTools会添加tool消息，导致最后一条不是assistant
            const lastAssistantMsg = [...session.messages].reverse().find(m => m.role === 'assistant');

            if (lastAssistantMsg) {
              assistantMsgId = lastAssistantMsg.id;
              accumulatedContent = lastAssistantMsg.content || '';
              console.log(`[ChatStore] Resumed assistant message ${assistantMsgId}, initial content length: ${accumulatedContent.length}`);
            } else {
              // Fallback: 如果找不到assistant消息（不应该发生），创建新的
              console.warn('[ChatStore] Resumption: No assistant message found, creating new one');
              assistantMsgId = `msg_ai_${Date.now()}`;
              get().addMessage(sessionId, {
                id: assistantMsgId,
                role: 'assistant',
                content: '',
                createdAt: Date.now(),
                modelId: modelId,
                ragReferences: [],
              });
            }
          } else {
            assistantMsgId = `msg_ai_${Date.now()}`;
            const assistantMsg: Message = {
              id: assistantMsgId,
              role: 'assistant',
              content: '',
              createdAt: Date.now(),
              modelId: modelId,
              ragReferences: [], // Initialize RAG references
            };
            get().addMessage(sessionId, assistantMsg);
          }

          const currentAssistantMsgId = assistantMsgId; // Keep closure ref
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
            let searchContext = '';
            let initialCitations: { title: string; url: string; source?: string }[] = [];

            // ✅ Phase 4b: 使用 context-builder 子模块
            if (options?.webSearch) {
              const searchResult = await performClientSideSearch(content, provider.type);
              searchContext = searchResult.context;
              initialCitations = searchResult.sources;
              // 将客户端搜索结果赋值给 accumulatedCitations
              if (searchResult.sources.length > 0) {
                accumulatedCitations = searchResult.sources;
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
            const apiMessage = {
              content,
              images: normalizedImages,
              files: processedFiles.length > 0 ? processedFiles : undefined // ✅ Include files for LLM client
            };

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
                // ✅ 由 RagStore 统一接管进度展示，移除旧的 updateMessageContent 进度初始化
                // get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, { stage: 'rewriting', percentage: 0 }, [], true);

                // For Super Assistant, force global search AND enable docs/memory
                const isSuperAssistant = sessionId === 'super_assistant';
                const effectiveRagOptions = {
                  ...finalRagOptions,
                  isGlobal: isSuperAssistant ? true : finalRagOptions.isGlobal,
                  enableDocs: isSuperAssistant ? true : finalRagOptions.enableDocs, // 🔑 强制开启文档
                  enableMemory: isSuperAssistant ? true : finalRagOptions.enableMemory, // 🔑 强制开启记忆
                  ragConfig: agent.ragConfig, // ✅ 关键：传入特定助手的 RAG 配置
                  onProgress: (stage: string, percentage: number, subStage?: string, networkStats?: any) => {
                    // ✅ 更新详细进度（包含原子步骤和流量）
                    const { updateProcessingState } = useRagStore.getState();
                    updateProcessingState({
                      sessionId,
                      stage: stage as any,
                      progress: percentage,
                      subStage,
                      networkStats
                    }, assistantMsgId);
                  },
                };

                // ✅ 1.1 预触发：意图分析子阶段
                useRagStore.getState().updateProcessingState({
                  sessionId,
                  status: 'retrieving',
                  stage: 'rewriting',
                  subStage: 'INTENT',
                  progress: 2
                }, assistantMsgId);

                // 🔑 CRITICAL FIX: Yield thread to allow UI events (Navigation/Back) to process
                // RAG retrieval involves heavy SQLite ops which can block the JS thread preventing navigation
                await new Promise(resolve => setTimeout(resolve, 0));

                // 🔑 CRITICAL FIX: Add safe timeout (30s) to prevent infinite hanging
                // If DB is locked or logic hangs, this ensures the UI recovers
                const retrievalPromise = MemoryManager.retrieveContext(
                  apiMessage.content,
                  sessionId,
                  effectiveRagOptions,
                );

                const timeoutPromise = new Promise<any>((_, reject) =>
                  setTimeout(() => reject(new Error('RAG Retrieval Timeout (30s)')), 30000)
                );

                const {
                  context: retrievedContext,
                  references,
                  metadata,
                  billingUsage,
                } = await Promise.race([retrievalPromise, timeoutPromise]);

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
                  { ragReferences, ragReferencesLoading: false, ragMetadata: metadata }
                );

                // ✅ 后置同步：将检索到的引用数存入 ProcessingHistory 以供指示器显示
                // 使用 'retrieved' 状态而不是 'completed'，以避免在生成期间显示“已归档”
                useRagStore.getState().updateProcessingState({
                  status: 'retrieved',
                  chunks: ragReferences.map(r => r.content || '')
                }, assistantMsgId);
              } catch (e) {
                // ✅ 故障容错：立即清理处理状态，防止指示器卡死在检索中
                useRagStore.getState().updateProcessingState({ status: 'idle' }, assistantMsgId);
                console.error('RAG Retrieval failed:', e);
                get().updateMessageContent(
                  sessionId,
                  assistantMsgId,
                  '',
                  { ragReferences: [], ragReferencesLoading: false }
                );
              }
            } else {
              console.log('[RAG DEBUG] RAG已禁用，跳过检索');
            }


            // 4. 准备上下文 (Prepare Context)
            // ✅ Only get skills if tools are enabled
            const toolsEnabled = options?.toolsEnabled ?? session.options?.toolsEnabled ?? true;
            const availableSkills = toolsEnabled ? skillRegistry.getEnabledSkills() : [];

            // 💉 Refactor: Use centralized context builder
            const isNativeWebSearchProvider = !!(provider as any).nativeWebSearch;
            const finalSystemPrompt = buildSystemPrompt(
              agent,
              get().getSession(sessionId) || session,
              ragContext,
              searchContext,
              provider,
              isNativeWebSearchProvider,
              availableSkills
            );

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

            for (let i = 0; i < historyMsgs.length; i++) {
              const msg = historyMsgs[i];
              if (msg.role === 'system') continue;

              const apiMsg: any = {
                role: msg.role,
                content: await formatContent(msg.content, msg.images)
              };

              if (msg.reasoning) apiMsg.reasoning = msg.reasoning;
              if (msg.thought_signature) apiMsg.thought_signature = msg.thought_signature;

              // 🛠️ CRITICAL FIX: Ensure Tool Call / Response Integrity for DeepSeek/Strict APIs
              if (msg.role === 'assistant' && msg.tool_calls && msg.tool_calls.length > 0) {
                // Only include tool_calls if they are followed by tool messages in this session
                // This prevents "hanging tool calls" which DeepSeek rejects with 400
                const toolCallIds = new Set(msg.tool_calls.map(tc => tc.id));
                const answeredIds = new Set<string>();

                // Look ahead in historyMsgs to see if these calls are answered
                for (let j = i + 1; j < historyMsgs.length; j++) {
                  const aheadMsg = historyMsgs[j];
                  if (aheadMsg.role === 'tool' && aheadMsg.tool_call_id && toolCallIds.has(aheadMsg.tool_call_id)) {
                    answeredIds.add(aheadMsg.tool_call_id);
                  }
                  // If we hit another user or assistant message, stop searching for this group? 
                  // Actually OpenAI allows interleaved if the sequence is correct, but let's be strict.
                  if (aheadMsg.role === 'user' || (aheadMsg.role === 'assistant' && aheadMsg.tool_calls)) break;
                }

                if (answeredIds.size > 0) {
                  // Filter only answered ones (or include all if at least one answered? No, must all be answered for DeepSeek)
                  // Actually, if we truncate, we might only have PART of the results. 
                  // DeepSeek rule: "An assistant message with 'tool_calls' must be followed by tool messages responding to EACH..."
                  if (answeredIds.size === toolCallIds.size) {
                    apiMsg.tool_calls = msg.tool_calls;
                  } else {
                    console.warn('[AgentLoop] Stripping incomplete/hanging tool calls from history to prevent 400 error.');
                    apiMsg.tool_calls = undefined;
                  }
                } else {
                  // No answers found in our current historyMsgs slice
                  apiMsg.tool_calls = undefined;
                }
              }

              if (msg.role === 'tool') {
                // Only include tool result if its assistant caller is also in this slice?
                // Actually, according to OpenAI, a tool message MUST be preceded by the assistant message.
                // If we truncated the assistant message but kept the tool, it's also a 400.
                const hasAssistant = contextMsgs.some(m => m.role === 'assistant' && m.tool_calls?.some((tc: any) => tc.id === msg.tool_call_id));
                if (hasAssistant) {
                  apiMsg.tool_call_id = msg.tool_call_id;
                  apiMsg.name = msg.name;
                } else {
                  continue; // Skip orphan tool results
                }
              }

              contextMsgs.push(apiMsg);

              // 🛠️ CRITICAL: If this assistant message has nested tool results in its executionSteps,
              // we MUST inject them into the context even if they aren't in historyMsgs.
              if (msg.role === 'assistant' && msg.executionSteps && msg.tool_calls) {
                const nestedResults = msg.executionSteps.filter(s => s.type === 'tool_result' || s.type === 'error');

                msg.tool_calls.forEach(tc => {
                  // Check if this tool_call ID is already answered in contextMsgs (next messages)
                  const isAlreadyAnswered = historyMsgs.slice(i + 1).some(m => m.role === 'tool' && m.tool_call_id === tc.id);

                  if (!isAlreadyAnswered) {
                    // 🧐 Use toolCallId for precise matching
                    const result = nestedResults.find(r => r.toolCallId === tc.id);
                    if (result) {
                      contextMsgs.push({
                        role: 'tool',
                        tool_call_id: tc.id,
                        name: tc.name,
                        content: result.content || 'Action successful.'
                      });
                    }
                  }
                });
              }
            }

            // Add user message to context
            console.log('[AgentLoop] Finalizing User Message Content:', {
              length: apiMessage.content.length,
              isMerged: apiMessage.content.includes('You are NeuralFlow'),
              isResumption: options?.isResumption
            });

            // ✅ 虚拟拆分架构关键修复：resumption模式下不添加空用户消息
            if (!options?.isResumption || apiMessage.content.trim()) {
              contextMsgs.push({
                role: 'user',
                content: await formatContent(apiMessage.content, apiMessage.images),
                files: apiMessage.files // ✅ Pass files to contextMsgs
              });
            } else {
              console.log('[AgentLoop] Resumption: Skipping empty user message in contextMsgs');
            }

            // 🆕 续杯机制：根据模型类型注入隐式续杯提示（不存储到消息历史，仅在上下文中）
            if (options?.isContinuationApproval) {
              const modelFamily = inferModelFamily(provider.type, modelId);
              const continuationPrompt = getContinuationPrompt(modelFamily);
              contextMsgs.push({ role: 'user', content: continuationPrompt });
              console.log('[AgentLoop] Injected model-specific continuation prompt:', modelFamily);
            }


            // 🔑 Phase 3.5: Apply Model-Specific System Prompt Enhancements
            // 使用FormatterFactory对消息历史进行模型特定优化
            // 特别是为Gemini Pro注入manage_task详细格式规范
            try {
              const formatter = FormatterFactory.getFormatter(
                provider.type as any,  // ApiProviderType -> ProviderType (兼容处理)
                provider.name          // 使用provider.name作为模型名称
              );
              // formatHistory会处理system消息的增强
              const formattedMsgs = formatter.formatHistory(contextMsgs as any[]);
              contextMsgs = formattedMsgs as any[];
              console.log('[FormatterFactory] Applied model-specific enhancements:', {
                provider: provider.type,
                modelName: provider.name,
                messageCount: contextMsgs.length
              });
            } catch (error) {
              console.warn('[FormatterFactory] Failed to apply enhancements, using original messages:', error);
              // 降级：继续使用原始消息
            }

            // =====================================================================================
            // Phase 4: Agentic Loop Implementation
            // =====================================================================================

            /**
             * 虚拟拆分Assistant+Tool序列
             * 
             * OpenAI API规范要求：每个assistant with tool_calls必须紧接对应的tool messages
             * UI设计要求：一个assistant气泡包含所有tool_calls
             * 
             * 解决方案：存储层保持单assistant（UI友好），API层动态拆分成多个assistant+tool对
             * 
             * @param rawSegment - session.messages提取的原始消息（1个assistant + N个tool）
             * @param parser - StreamParser实例
             * @param accumulatedContent - 累积的content，用于第一个assistant
             * @returns 虚拟拆分后的消息数组
             */
            const virtualSplitAssistantToolPairs = (
              rawSegment: any[],
              parser: any,
              accumulatedContent: string,
              allowedSkillIds: string[]
            ): any[] => {
              const virtualSegment: any[] = [];
              let isFirstAssistant = true;

              for (const m of rawSegment) {
                // ✅ 处理user消息（直接保留）
                if (m.role === 'user') {
                  virtualSegment.push({
                    role: 'user',
                    content: m.content
                  });
                  continue;
                }

                if (m.role === 'assistant') {
                  const toolCalls = (m as any).tool_calls || [];

                  if (toolCalls.length === 0) {
                    // 没有tool_calls，直接添加
                    const msg: any = {
                      role: 'assistant',
                      content: isFirstAssistant ? parser.getCleanContent(accumulatedContent) : m.content,
                    };
                    if ((m as any).reasoning) msg.reasoning_content = (m as any).reasoning;
                    if ((m as any).thought_signature) msg.thought_signature = (m as any).thought_signature;
                    virtualSegment.push(msg);
                    isFirstAssistant = false;
                  } else {
                    // 有tool_calls，拆分成多个assistant+tool对
                    for (let tcIdx = 0; tcIdx < toolCalls.length; tcIdx++) {
                      const tc = toolCalls[tcIdx];

                      // 🛡️ 历史过滤：如果该工具在当前轮次中已禁用，则跳过该 call-response 对
                      // 防止模型通过历史记忆“破解”禁用限制
                      if (!allowedSkillIds.includes(tc.name)) {
                        console.log(`[VirtualSplit] Filtering disabled tool from history: ${tc.name}`);
                        continue;
                      }

                      // ✅ 创建虚拟assistant（只包含单个tool_call）
                      const virtualAssistant: any = {
                        role: 'assistant',
                        content: isFirstAssistant && tcIdx === 0
                          ? parser.getCleanContent(accumulatedContent)
                          : '',
                        tool_calls: [tc], // 只包含当前tool_call
                      };

                      // 🔑 CRITICAL: 字段继承规则
                      // - thought_signature: 所有assistant都必须继承（VertexAI要求）
                      // - reasoning: 内部字段，所有assistant都必须有（DeepSeek要求，第一个有内容，其他为空）
                      // - DeepSeekClient会将reasoning转换为reasoning_content发送给API

                      if (isFirstAssistant && tcIdx === 0) {
                        // 第一个assistant：包含实际reasoning和thought_signature
                        virtualAssistant.reasoning = (m as any).reasoning || '';  // ✅ 设置reasoning而非reasoning_content
                        if ((m as any).thought_signature) {
                          virtualAssistant.thought_signature = (m as any).thought_signature;
                        }
                      } else {
                        // 后续assistant：空reasoning，但仍需继承thought_signature
                        virtualAssistant.reasoning = '';  // ✅ 设置reasoning而非reasoning_content
                        if ((m as any).thought_signature) {
                          virtualAssistant.thought_signature = (m as any).thought_signature;
                        }
                      }

                      virtualSegment.push(virtualAssistant);

                      // ✅ 查找对应的tool消息
                      const toolMsg = rawSegment.find(tm =>
                        tm.role === 'tool' && (tm as any).tool_call_id === tc.id
                      );

                      if (toolMsg) {
                        virtualSegment.push({
                          role: 'tool',
                          content: toolMsg.content,
                          tool_call_id: (toolMsg as any).tool_call_id,
                          name: (toolMsg as any).name,
                        });
                      }

                      if (tcIdx === 0) isFirstAssistant = false;
                    }
                  }
                }
                // tool消息已在上面处理，跳过
              }

              // 🔍 调试日志：打印所有虚拟assistant消息
              const debugAssistants = virtualSegment.filter(m => m.role === 'assistant');
              console.log('[VirtualSplit] Created assistants:', debugAssistants.length);
              debugAssistants.forEach((msg, idx) => {
                console.log(`[VirtualSplit] Assistant ${idx}:`, {
                  hasReasoningContent: msg.reasoning_content !== undefined,
                  reasoningLength: msg.reasoning_content?.length || 0,
                  hasThoughtSignature: msg.thought_signature !== undefined,
                  hasToolCalls: msg.tool_calls !== undefined,
                  toolCallsCount: msg.tool_calls?.length || 0
                });
              });

              return virtualSegment;
            };

            const MAX_LOOP_COUNT = useSettingsStore.getState().maxLoopCount || 5;
            // let loopCount = 0; // 🛡️ 已迁移到下方的 session 持久化逻辑
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
                { reasoning: accumulatedReasoning, citations: accumulatedCitations, ragReferences, ragReferencesLoading: false, executionSteps: loopExecutionSteps }
              );
            }

            // Helper to update execution steps in store
            const updateSteps = (newStep: ExecutionStep) => {
              // 🛡️ Critical: Sync from store first to avoid discarding tool results added by executeTools
              const sessionRef = get().getSession(sessionId);
              const msgRef = sessionRef?.messages.find(m => m.id === currentAssistantMsgId);
              const baseSteps = msgRef?.executionSteps || loopExecutionSteps;

              const index = baseSteps.findIndex(s => s.id === newStep.id);
              let updatedSteps = [...baseSteps];

              if (index > -1) {
                updatedSteps[index] = newStep;
              } else {
                updatedSteps.push(newStep);
              }

              loopExecutionSteps = updatedSteps; // Sync local tracker

              get().updateMessageContent(
                sessionId,
                currentAssistantMsgId,
                accumulatedContent,
                { reasoning: accumulatedReasoning, citations: accumulatedCitations, ragReferences, ragReferencesLoading: false, executionSteps: updatedSteps }
              );
            };


            const maxLoops = useSettingsStore.getState().maxLoopCount || 20;
            // 🆕 Phase 2.6: Infinite Loop Support
            // If setting is >= 100, treat as Infinite (set to 9999 internal limit)
            const continuationBudget = session?.continuationBudget || 0;
            const isInfiniteMode = maxLoops >= 100;
            const effectiveMaxLoops = isInfiniteMode ? 9999 : (maxLoops + continuationBudget);

            // 🔄 Loop Entry Point
            // Reset loop counter if starting fresh or continuing manually
            if ((options as any)?.loopCount !== undefined) {
              get().updateSession(sessionId, { currentLoopCount: (options as any).loopCount });
            } else if (get().getSession(sessionId)?.loopStatus !== 'paused') {
              // Only reset if NOT resuming from a pause
              get().updateSession(sessionId, { currentLoopCount: 0 });
            }

            // 🛡️ RECOVERY: Check for pending intervention
            // Declaring session here again, but using let if needed, or just reusing
            // We need to be careful about shadowing 'session' variable from upper scope?
            // Actually 'session' is defined in line 1018. Let's use get().getSession(sessionId) directly to avoid confusion.
            const initialSession = get().getSession(sessionId);
            if (initialSession?.loopStatus === 'paused' && initialSession.pendingIntervention) {
              // If the user has just replied (which triggers generateMessage), 
              // we should clear the pause and intervention flags and continue.
              console.log('[AgentLoop] Resuming form PAUSED state due to user input');
              get().updateSession(sessionId, {
                loopStatus: 'running',
                pendingIntervention: undefined
              });
            }

            // 🛡️ 逻辑修复：从 session 获取已执行轮数，支持续杯后的连续性
            // 🛡️ 逻辑修复：仅在续杯模式下继承轮数，否则新消息重置为 0
            let loopCount = (options?.isResumption && initialSession?.currentLoopCount)
              ? initialSession.currentLoopCount
              : 0;

            // 🆕 Phase 4: 死循环检测机制
            // 追踪连续相同工具调用，若超过阈值则自动中断
            const MAX_CONSECUTIVE_SAME_ACTIONS = 3;
            let lastToolCallSignature = '';
            let consecutiveSameActionCount = 0;

            while (get().activeRequests[sessionId]) {
              loopCount++;
              // 同步当前轮数到会话状态（用于 UI 显示和后续续杯参考）
              get().updateSession(sessionId, { currentLoopCount: loopCount });

              const latestSession = get().getSession(sessionId);
              if (!latestSession) break;

              const targetMsg = latestSession.messages.find(m => m.id === currentAssistantMsgId);
              if (!targetMsg) break;

              // 🔄 关键修复：从最新消息状态同步累积内容变量。
              // 这防止了背景任务（如图表注入）修改 Store 后，流式循环因持有旧内容副本而导致内容“回退”或截断。
              accumulatedContent = targetMsg.content || '';

              let toolCalls: ToolCall[] | undefined;
              let turnThoughtSignature = '';

              console.log(`[AgentLoop] Turn ${loopCount}/${effectiveMaxLoops} (base: ${maxLoops}, budget: ${continuationBudget})`);

              // 🛡️ Continuation Check
              const SOFT_LIMIT = 20;

              // Soft Limit Check (Passive Notification)
              if (loopCount >= SOFT_LIMIT && !session?.isLongRunning) {
                console.log(`[AgentLoop] Soft limit (${SOFT_LIMIT}) reached. Activating passive monitoring.`);
                get().updateSession(sessionId, { isLongRunning: true });
              }

              // Hard Limit Check (Blocking Approval)
              if (loopCount > effectiveMaxLoops) {
                console.log(`[AgentLoop] Max loops (${effectiveMaxLoops}) reached. Pausing for continuation.`);

                get().setApprovalRequest(sessionId, {
                  type: 'continuation',
                  toolName: 'Loop Limit',
                  args: {},
                  reason: `已达到最大执行轮数 (${effectiveMaxLoops})`
                });
                get().setLoopStatus(sessionId, 'waiting_for_approval');

                // Clear long running state on pause
                get().updateSession(sessionId, { isLongRunning: false });

                // ✅ 关键：同步向 Timeline 推送一个待续步骤
                const interventionStep: ExecutionStep = {
                  id: `cont_${Date.now()}`,
                  type: 'intervention_required',
                  toolName: 'Loop Limit',
                  content: `已达到执行轮数上限 (${maxLoops})。是否继续执行？`,
                  timestamp: Date.now()
                };

                // Persist state
                get().updateMessageContent(
                  sessionId,
                  currentAssistantMsgId,
                  accumulatedContent,
                  {
                    thought_signature: turnThoughtSignature || targetMsg.thought_signature,
                    tool_calls: toolCalls,
                    executionSteps: [...(targetMsg.executionSteps || []), interventionStep],
                    loopCount
                  }
                );

                break;
              }


              // Steerable Agent Loop: Intervention
              const pending = get().getSession(sessionId)?.pendingIntervention;
              if (pending) {
                console.log('[AgentLoop] Injecting User Intervention:', pending);
                currentMessages.push({ role: 'system' as any, content: `[IMMEDIATE USER INTERVENTION]: ${pending}` });
                get().setPendingIntervention(sessionId, undefined);
              }

              // 4. Get Enabled Skills (🆕 Phase 4: 全链路工具控制)
              // 整合全局开关 (Master Toggle) + 会话级开关 (Session Toolbox) + 模型能力 (Native Search)
              const isNativeWebSearchProvider = provider.type === 'gemini' || provider.type === 'google';
              const availableSkills = skillRegistry.getEnabledSkillsForSession(latestSession, {
                nativeWebSearch: isNativeWebSearchProvider
              });

              console.log('[AgentLoop] Available Skills:', availableSkills.map(s => s.id),
                { nativeWebSearch: isNativeWebSearchProvider, total: availableSkills.length });

              // 🆕 Architecture Upgrade: Dynamic System Prompt Injection
              // Rebuild System Prompt at the start of each turn to reflect latest Task State and Tools
              const dynamicSystemPrompt = buildSystemPrompt(
                agent,
                latestSession, // Uses fresh session state (including activeTask)
                ragContext,    // Keep static for now (efficiency)
                searchContext, // Keep static
                provider,
                isNativeWebSearchProvider,
                availableSkills // Uses fresh skills (including newly registered ones)
              );

              // Update the System Message (Index 0) in currentMessages
              // NOTE: currentMessages is initialized outside loop, but we must update it
              if (currentMessages.length > 0 && currentMessages[0].role === 'system') {
                // If searchContext exists, it was appended. We should preserve it.
                // See lines 628-634:
                // content: finalSystemPrompt + '\n\n' + searchContext
                let newContent = dynamicSystemPrompt;
                if (searchContext) {
                  newContent += '\n\n' + searchContext;
                }
                currentMessages[0].content = newContent;
                console.log('[AgentLoop] Dynamic System Prompt updated with fresh state');
              }


              // 5. Stream Chat
              let reasoningFromThisTurn = '';

              // 🔑 Check for Abort BEFORE streamChat
              if (!get().activeRequests[sessionId]) {
                console.log('[AgentLoop] Detected abort BEFORE streamChat, breaking loop');
                break;
              }

              // 🧠 Optimization: Use Array Buffer for Content to reduce GC pressure
              let contentBuffer: string[] = [];
              let turnContent = ''; // RE-ADDED: Sync with contentBuffer for history persistence

              const allowedSkillIds = availableSkills.map(s => s.id);

              // 🧠 StreamParser for incremental parsing
              const parser = new StreamParser(provider.type as any);
              let planParsed = false;

              // 🔑 关键修复 2: 分离并增加限流间隔 (Fix UI Freeze)
              // 避免高频 Zustand 更新阻塞 JS 线程 (特别是 React Native)
              // Content: 200ms (5fps) - 足够人眼阅读，且大幅减轻渲染压力
              // Timeline: 500ms (2fps) - 思考过程不需要极高频刷新
              let lastContentUpdateTime = 0;
              let lastTimelineUpdateTime = 0;
              const CONTENT_UPDATE_INTERVAL = 33;
              const TIMELINE_UPDATE_INTERVAL = 100; // 10fps — 与正文节流保持一致

              // 🐶 Watchdog: Deadlock Detection
              // If no token received for 30s, abort generation
              let lastActivityTime = Date.now();
              const WATCHDOG_TIMEOUT = 30000;
              const watchdogInterval = setInterval(() => {
                const isActive = get().activeRequests[sessionId];
                const session = get().getSession(sessionId);
                const msg = session?.messages.find(m => m.id === currentAssistantMsgId);

                // 🛑 Stop if done or error
                if (!isActive || msg?.isError) {
                  clearInterval(watchdogInterval);
                  return;
                }

                if (Date.now() - lastActivityTime > WATCHDOG_TIMEOUT) {
                  // 🐶 Soft Timeout: Warn instead of Abort
                  console.warn('[ChatStore] Watchdog detected stall. Triggering soft timeout warning...');

                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    { isError: false, isLongWait: true, loopCount }
                  );

                  // Keep checking (don't clear interval), so if it *eventually* recovers, we can clear the warning.
                  // Or if user aborts manually.
                }
              }, 5000);

              await client.streamChat(
                currentMessages as any,
                (token) => {
                  // Check for abort
                  const currentState = get();
                  if (!currentState.activeRequests[sessionId]) return;

                  const now = Date.now();
                  lastActivityTime = now; // 🐶 Feed the Watchdog

                  // ✅ Auto-Clear Soft Timeout Warning if tokens arrive
                  if (accumulatedContent) { // Optimization: only if we have content update pending
                    // If we were in long wait state, we should ideally clear it. 
                    // But updateMessageContent is expensive. 
                    // Let the next content update cycle handle it implicitly? 
                    // No, we need explicit clear if we want instant feedback.
                    // But since we update content frequently, passing isLongWait=false in parser loop is better.
                  }

                  // 5.0 Capture Metadata
                  if (token.usage) {
                    accumulatedUsage = token.usage;
                  }
                  if (token.thought_signature) {
                    console.log('[AgentLoop] Captured thought_signature from provider:', token.thought_signature);
                    turnThoughtSignature = token.thought_signature;
                  }

                  // 5.1 Process via StreamParser
                  const parseResult = parser.process(token.content || '');

                  if (parseResult.content) {
                    // Buffer content instead of string concat
                    contentBuffer.push(parseResult.content);
                  }

                  if (parseResult.reasoning) {
                    // [Deprecated] StreamParser no longer produces reasoning output.
                    // Kept as a safety net for any edge cases.
                    reasoningFromThisTurn += parseResult.reasoning;
                  }
                  if (token.reasoning) {
                    // Primary source: Provider layer (ThinkingDetector) ensures clean separation
                    reasoningFromThisTurn += token.reasoning;
                  }

                  // 🆕 Phase 3: 捕获 Gemini 原生搜索结果并生成 ExecutionStep
                  // 用于在 Timeline 中可视化原生 Google Search（紫色 Globe 图标区分）
                  if (token.citations && token.citations.length > 0) {
                    const searchStepId = `native_search_${Date.now()}`;
                    // 1. 生成"正在搜索"调用步骤
                    updateSteps({
                      id: searchStepId,
                      type: 'native_search',
                      content: '正在使用 Google 原生网络搜索...',
                      timestamp: Date.now()
                    } as any);
                    // 2. 生成"搜索结果"步骤
                    updateSteps({
                      id: `${searchStepId}_result`,
                      type: 'native_search_result',
                      content: `Found ${token.citations.length} sources.`,
                      data: { sources: token.citations },
                      timestamp: Date.now() + 100
                    } as any);
                    console.log('[AgentLoop] Captured native search citations:', token.citations.length);
                  }

                  if (parseResult.toolCalls) {
                    if (!toolCalls) toolCalls = [];
                    // 🛡️ 幂等性更新：确保相同 ID 的工具调用仅保留最新版本，不重复累加
                    parseResult.toolCalls.forEach(tc => {
                      const idx = toolCalls!.findIndex(existing => existing.id === tc.id);
                      if (idx > -1) {
                        toolCalls![idx] = tc;
                      } else {
                        toolCalls!.push(tc);
                      }
                    });
                  }
                  if (token.toolCalls) {
                    if (!toolCalls) toolCalls = [];
                    token.toolCalls.forEach(tc => {
                      const idx = toolCalls!.findIndex(existing => existing.id === tc.id);
                      if (idx > -1) {
                        toolCalls![idx] = tc;
                      } else {
                        toolCalls!.push(tc);
                      }
                    });
                  }

                  // 5.2 Handle Plan (Once)
                  if (parseResult.plan && !planParsed) {
                    console.log('[AgentLoop] Plan detected by StreamParser');
                    const steps = parseResult.plan.map((item: any, idx: number) => ({
                      id: item.id || `plan_step_${Date.now()}_${idx}`,
                      title: item.title || `Step ${idx + 1}`,
                      status: item.status || 'pending',
                      description: item.description
                    }));

                    if (steps.length > 0) {
                      // Create Active Task (Session Level - Keep for compatibility)
                      const session = get().getSession(sessionId);
                      const newActiveTask: TaskState = {
                        title: 'Plan (Auto-Generated)',
                        status: 'in-progress',
                        progress: 0,
                        steps: steps as any,
                        createdAt: Date.now(),
                        updatedAt: Date.now()
                      };

                      if (session && !session.activeTask) {
                        get().updateSession(sessionId, {
                          activeTask: newActiveTask
                        });
                      }

                      // ✅ Sync to Message Level immediately
                      get().updateMessageContent(
                        sessionId,
                        currentAssistantMsgId,
                        accumulatedContent,
                        { ragReferencesLoading: false, planningTask: newActiveTask, loopCount }
                      );

                      // Update Timeline
                      steps.forEach((step: any, index: number) => {
                        updateSteps({
                          id: step.id || `plan_step_exec_${index}`,
                          type: 'plan_item',
                          content: step.title,
                          timestamp: Date.now()
                        });
                      });
                    }
                    planParsed = true;
                  }

                  // 5.3 Throttled UI Updates

                  // Update Content Bubble
                  if (contentBuffer.length > 0 && (now - lastContentUpdateTime > CONTENT_UPDATE_INTERVAL)) {
                    const newContent = contentBuffer.join('');
                    accumulatedContent += newContent;
                    turnContent += newContent; // SYNC: Update history buffer
                    // Clear buffer after flush
                    contentBuffer = [];

                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      { tokens: token.usage, tool_calls: token.toolCalls, isLongWait: false, loopCount }
                    );
                    lastContentUpdateTime = now;
                  }

                  // Update Timeline (Reasoning)
                  if ((parseResult.reasoning || token.reasoning) && (now - lastTimelineUpdateTime > TIMELINE_UPDATE_INTERVAL)) {
                    const stepId = `think_turn_${loopCount}`;
                    updateSteps({
                      id: stepId,
                      type: 'thinking',
                      content: reasoningFromThisTurn,
                      timestamp: Date.now()
                    });

                    // Clear bubble reasoning if we show it in timeline to avoid redundancy
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      { tokens: token.usage }
                    );
                    lastTimelineUpdateTime = now;
                  }
                },
                (error) => { console.warn('Stream error', error); },
                {
                  skills: availableSkills,
                  webSearch: options?.webSearch ?? latestSession.options?.webSearch,
                  reasoning: options?.reasoning ?? latestSession.options?.reasoning,
                  inferenceParams: {
                    temperature: agent.params?.temperature || 0.7,
                  } as any
                }
              );

              // 🐶 Stop Watchdog after stream completes
              clearInterval(watchdogInterval);


              // 🏁 关键修复：冲刷缓冲区残余内容
              if (contentBuffer.length > 0) {
                const tailContent = contentBuffer.join('');
                accumulatedContent += tailContent;
                turnContent += tailContent;
                contentBuffer = [];
              }

              // 🏁 关键修复：冲刷最后的思考记录到 Timeline，防止因 500ms 节流导致的截断
              if (reasoningFromThisTurn) {
                updateSteps({
                  id: `think_turn_${loopCount}`,
                  type: 'thinking',
                  content: reasoningFromThisTurn,
                  timestamp: Date.now()
                });
              }

              // 🏁 Turn Termination: Ensure all content and tool calls are synchronized to the Store
              // We calculate the final text (removing thinking tags) and perform a blocking flush.
              const finalDisplayContent = accumulatedContent.replace(/<plan>[\s\S]*?<\/plan>/gi, '').trim();

              get().updateMessageContent(
                sessionId,
                currentAssistantMsgId,
                finalDisplayContent,
                {
                  tokens: accumulatedUsage,
                  thought_signature: turnThoughtSignature || targetMsg.thought_signature,
                  planningTask: latestSession.activeTask,
                  tool_calls: toolCalls,
                  executionSteps: loopExecutionSteps,
                  isLongWait: false,
                  loopCount
                }
              );

              // 🛡️ 关键修复：刷新本地步骤列表，确保下一轮循环能看到工具执行结果
              if (get().flushMessageUpdates) {
                get().flushMessageUpdates(sessionId, currentAssistantMsgId);
                const refreshedMsg = get().getSession(sessionId)?.messages.find(m => m.id === currentAssistantMsgId);
                if (refreshedMsg?.executionSteps) {
                  loopExecutionSteps = refreshedMsg.executionSteps;
                  console.log('[AgentLoop] Synchronized loopExecutionSteps from Store:', loopExecutionSteps.length);
                }
              }

              // ✅ Mandatory Flush: ensure UI & background tasks see the latest state immediately
              if (get().flushMessageUpdates) {
                get().flushMessageUpdates(sessionId, currentAssistantMsgId);
              }

              // ✅ Phase 4c: 竞态条件修复 - 强制刷新消息状态
              // 确保后续工具执行时能读到最新的 thought_signature 和 tool_calls
              if (get().flushMessageUpdates) {
                get().flushMessageUpdates(sessionId, currentAssistantMsgId);
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
                // Fallback Phase 1: Robust XML Parser (DeepSeek/Kimi style)
                const xmlCallRegex = /<tool_call>([\s\S]*?)<\/tool_call>/gi;
                let currentXmlMatch;

                while ((currentXmlMatch = xmlCallRegex.exec(turnContent)) !== null) {
                  const xmlBlock = currentXmlMatch[1];
                  const nameMatch = /<tool_name>([\s\S]*?)<\/tool_name>/i.exec(xmlBlock);

                  if (nameMatch) {
                    const funcName = nameMatch[1].trim();
                    const args: Record<string, any> = {};

                    // Parse key-value parameters: <parameter>\s*<key>([\s\S]*?)<\/key>\s*<value>([\s\S]*?)<\/value>\s*<\/parameter>
                    const paramRegex = /<parameter>\s*<key>([\s\S]*?)<\/key>\s*<value>([\s\S]*?)<\/value>\s*<\/parameter>/gi;
                    let paramMatch;
                    while ((paramMatch = paramRegex.exec(xmlBlock)) !== null) {
                      const key = paramMatch[1].trim();
                      const val = paramMatch[2].trim();
                      try {
                        // Try parsing as JSON if it looks like it, else treat as string
                        args[key] = (val.startsWith('{') || val.startsWith('[')) ? JSON.parse(val) : val;
                      } catch (e) {
                        args[key] = val;
                      }
                    }

                    // Also support simple key=value or JSON inside the block if no explicit parameters found
                    if (Object.keys(args).length === 0) {
                      const jsonMatch = /\{[\s\S]*\}/.exec(xmlBlock);
                      if (jsonMatch) {
                        try {
                          Object.assign(args, JSON.parse(jsonMatch[0]));
                        } catch (e) { }
                      }
                    }

                    extractedCalls.push({
                      id: `xml_call_${Date.now()}_${extractedCalls.length}`,
                      name: funcName,
                      arguments: args
                    });

                    // Strip this block from content
                    turnContent = turnContent.replace(currentXmlMatch[0], '').trim();
                  }
                }

                // Also search for <call tool="...">...</call> style (DeepSeek 2nd style)
                const altXmlRegex = /<call\s+tool=["'](.*?)["']\s*>([\s\S]*?)<\/call>/gi;
                let altXmlMatch;
                while ((altXmlMatch = altXmlRegex.exec(turnContent)) !== null) {
                  const funcName = altXmlMatch[1];
                  try {
                    const args = JSON.parse(altXmlMatch[2].trim());
                    extractedCalls.push({
                      id: `xml_alt_${Date.now()}_${extractedCalls.length}`,
                      name: funcName,
                      arguments: args
                    });
                    turnContent = turnContent.replace(altXmlMatch[0], '').trim();
                  } catch (e) { }
                }

                if (extractedCalls.length > 0) {
                  toolCalls = extractedCalls;
                } else {
                  // Backward compatibility for simple JSON in code blocks
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
                }

                if (extractedCalls.length > 0) {
                  console.log(`[AgentLoop] Fallback Parser found ${extractedCalls.length} calls:`,
                    extractedCalls.map(c => `${c.name}(${typeof c.arguments === 'string' ? 'str' : 'obj'})`)
                  );
                  toolCalls = extractedCalls;
                }
              }

              // Fallback Phase 2: Embedded JSON check (Waterfall if XML failed)
              // 5.4 No Fallback required - StreamParser handles it all.

              // Final Processing & Execution
              if (reasoningFromThisTurn) {
                accumulatedReasoning = '';
                // 🔑 关键修复：保存reasoning到assistant消息
                // 必须传递reasoningFromThisTurn而非空字符串，否则会覆盖原有reasoning
                get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, { tokens: accumulatedUsage, reasoning: reasoningFromThisTurn });
              }

              if (toolCalls && toolCalls.length > 0) {

                // 1. 🔑 任务状态预检测 (提前识别 Manage Task Complete)
                const isTaskComplete = toolCalls.some(tc =>
                  ((tc as any).name || (tc as any).function?.name) === 'manage_task' &&
                  (tc.arguments?.action === 'complete' || (tc as any).function?.arguments?.action === 'complete')
                );

                // 2. 🔑 智能文本分流 (识别“思考引导语”与“正式正文”)
                // 只有当文本较短且符合特征时才移入 Timeline，确保长篇总结保留在正文区
                // 增加英文引导词支持 (I will, Searching, etc.)
                const trimmed = accumulatedContent.trim();
                const isLikelyThinking = accumulatedContent.length < 600 &&
                  (trimmed.endsWith('...') ||
                    trimmed.endsWith('：') ||
                    trimmed.endsWith(':') ||
                    /^(我将|正在|首先|现在|已经|让我|接下来|I will|Searching|First|Now|Starting|Next)/i.test(trimmed));

                if (trimmed && !isTaskComplete && isLikelyThinking) {
                  console.log('[AgentLoop] Captured process text:', accumulatedContent.length);
                  const thinkingStep: any = {
                    id: `thinking_${Date.now()}`,
                    type: 'thinking',
                    content: accumulatedContent.trim(),
                    timestamp: Date.now()
                  };

                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    '', // Clear content since it's moved to timeline
                    { executionSteps: [...(targetMsg.executionSteps || []), thinkingStep] }
                  );
                  accumulatedContent = '';
                }

                // 3. 🔑 任务收尾与审批流逻辑
                if (isTaskComplete) {
                  console.log('[AgentLoop] Task marked as complete, stopping loop');

                  // 🧠 Auto-Injection: If final_summary exists in tool args but not in content, append it
                  const completeTool = toolCalls.find(tc =>
                    ((tc as any).name || (tc as any).function?.name) === 'manage_task' &&
                    (tc.arguments?.action === 'complete' || (tc as any).function?.arguments?.action === 'complete')
                  );

                  if (completeTool) {
                    const args = typeof completeTool.arguments === 'string'
                      ? JSON.parse(completeTool.arguments)
                      : (completeTool.arguments || (completeTool as any).function?.arguments);

                    if (args?.final_summary && !accumulatedContent.includes(args.final_summary)) {
                      console.log('[AgentLoop] Injecting final_summary into message content');
                      const summaryText = `\n\n**Mission Accomplished**\n${args.final_summary}`;
                      accumulatedContent += summaryText;
                      // Update immediately before breaking
                      get().updateMessageContent(
                        sessionId,
                        currentAssistantMsgId,
                        accumulatedContent,
                        {
                          tokens: accumulatedUsage,
                          thought_signature: turnThoughtSignature || targetMsg.thought_signature,
                          tool_calls: toolCalls
                        }
                      );
                    }
                  }

                  const executionMode = session.executionMode || 'auto';

                  if (executionMode !== 'auto') {
                    const highRiskInComplete: ToolCall[] = [];
                    const lowRiskInComplete: ToolCall[] = [];

                    for (const tc of toolCalls) {
                      const name = (tc as any).name || (tc as any).function?.name || '';
                      const skill = skillRegistry.getSkill(name);
                      if (skill?.isHighRisk) {
                        highRiskInComplete.push(tc);
                      } else {
                        lowRiskInComplete.push(tc);
                      }
                    }

                    // 先执行低风险工具（包括 manage_task）
                    if (lowRiskInComplete.length > 0) {
                      console.log('[AgentLoop] Task complete: Executing', lowRiskInComplete.length, 'low-risk tools first');
                      await get().executeTools(sessionId, lowRiskInComplete, currentAssistantMsgId);
                    }

                    // 高风险工具需要审批
                    if (highRiskInComplete.length > 0 && (executionMode === 'semi' || executionMode === 'manual')) {
                      console.log('[AgentLoop] Task complete: Pausing for', highRiskInComplete.length, 'high-risk tools approval');

                      const pendingApprovalToolIds = highRiskInComplete.map(tc => tc.id || `pending_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`);

                      get().setApprovalRequest(sessionId, {
                        toolName: highRiskInComplete.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                        args: highRiskInComplete.map(tc => (tc as any).arguments || (tc as any).function?.arguments),
                        reason: `Task completion requires approval for high-risk tools.`
                      });
                      get().setLoopStatus(sessionId, 'waiting_for_approval');

                      get().updateMessageContent(
                        sessionId,
                        currentAssistantMsgId,
                        accumulatedContent,
                        {
                          thought_signature: turnThoughtSignature || targetMsg.thought_signature,
                          tool_calls: toolCalls,
                          pendingApprovalToolIds
                        }
                      );
                      return; // 暂停等待审批
                    }
                  } else {
                    // Auto 模式直接执行
                    // 🔑 Check for Abort BEFORE executeTools
                    if (!get().activeRequests[sessionId]) {
                      console.log('[AgentLoop] Detected abort BEFORE executeTools, breaking loop');
                      break;
                    }
                    await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);
                  }
                  break; // 任务完成，退出循环
                }

                // 🔑 检测 manage_task create（任务创建，必须继续执行）
                const isTaskCreate = toolCalls.some(tc => {
                  if (tc.name !== 'manage_task') return false;
                  const args = typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments;
                  return args?.action === 'create';
                });

                if (isTaskCreate) {
                  console.log('[AgentLoop] Task created, will execute and rebuild messages before continuing');
                  await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);

                  // 🔑 关键修复：重构消息历史（包含tool results）再continue
                  // DeepSeek是无状态API，必须传递完整历史
                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    { thought_signature: turnThoughtSignature || targetMsg.thought_signature, tool_calls: toolCalls }
                  );

                  // 🔑 关键修复：虚拟拆分新增的assistant+tool（不重新提取整个session）
                  const latestSession = get().getSession(sessionId);
                  if (latestSession) {
                    // 🔥 从Turn 1开始：只提取当前Turn新增的assistant+tool
                    // 因为currentMessages已经包含了之前的历史
                    const currentUserMsgIdx = latestSession.messages.findIndex(m =>
                      m.role === 'user' && m.content === content
                    );

                    if (currentUserMsgIdx > -1) {
                      // ✅ 查找最新的assistant消息（currentAssistantMsgId）
                      const currentAssistantIdx = latestSession.messages.findIndex(m =>
                        m.id === currentAssistantMsgId
                      );

                      if (currentAssistantIdx > -1) {
                        // 提取当前assistant及其后的tool消息
                        const newSegment = latestSession.messages.slice(currentAssistantIdx);

                        console.log('[AgentLoop] Extracting new segment after task create:', {
                          currentAssistantIdx,
                          newSegmentLength: newSegment.length,
                          roles: newSegment.map(m => m.role).join(' -> ')
                        });

                        // ✅ 虚拟拆分新增的segment
                        const virtualNewSegment = virtualSplitAssistantToolPairs(
                          newSegment,
                          parser,
                          accumulatedContent,
                          allowedSkillIds
                        );

                        // ✅ 追加到currentMessages（不是替换）
                        currentMessages = [...currentMessages, ...virtualNewSegment];
                        console.log('[AgentLoop] Appended new segment after task create:', {
                          total: currentMessages.length,
                          newAssistantCount: virtualNewSegment.filter(m => m.role === 'assistant').length,
                          newToolCount: virtualNewSegment.filter(m => m.role === 'tool').length
                        });
                      }
                    }
                  }

                  // 🛡️ 轮数限制检查（由于已递增，直接检查）
                  if (loopCount >= effectiveMaxLoops) {
                    console.log(`[AgentLoop] Max loop count (${effectiveMaxLoops}) reached after task create`);
                    break;
                  }
                  console.log(`[AgentLoop] Continuing to Turn ${loopCount + 1} after task create, currentMessages count:`, currentMessages.length);
                  // 🔍 调试：打印currentMessages中的assistant消息
                  const currentAssistants = currentMessages.filter((m: any) => m.role === 'assistant');
                  console.log('[AgentLoop] Current assistants in messages:', currentAssistants.length);
                  currentAssistants.forEach((msg: any, idx: number) => {
                    console.log(`[AgentLoop] Assistant ${idx}:`, {
                      hasReasoningContent: msg.reasoning_content !== undefined,
                      reasoningLength: msg.reasoning_content?.length || 0,
                      hasToolCalls: msg.tool_calls !== undefined
                    });
                  });
                  continue; // 现在可以安全continue了
                }

                // Execute
                // Steerable Agent Loop: Check Approval
                const executionMode = session.executionMode || 'auto';
                let shouldPause = false;

                // ✅ CRITICAL FIX: 分离高风险和低风险工具
                const highRiskCalls: ToolCall[] = [];
                const lowRiskCalls: ToolCall[] = [];

                for (const tc of toolCalls) {
                  const name = (tc as any).name || (tc as any).function?.name || '';
                  const skill = skillRegistry.getSkill(name);
                  if (skill?.isHighRisk) {
                    highRiskCalls.push(tc);
                  } else {
                    lowRiskCalls.push(tc);
                  }
                }

                if (executionMode === 'manual') {
                  shouldPause = toolCalls.length > 0; // 所有工具都需要审批
                } else if (executionMode === 'semi') {
                  shouldPause = highRiskCalls.length > 0; // 只有高风险工具需要审批
                }

                // 🔍 DEBUG: 追踪审批逻辑
                console.log('[AgentLoop] Approval Check:', {
                  executionMode,
                  shouldPause,
                  highRiskCount: highRiskCalls.length,
                  lowRiskCount: lowRiskCalls.length,
                  toolNames: toolCalls.map((tc: any) => tc.name || tc.function?.name).join(', '),
                  isResumption: !!options?.isResumption
                });

                if (shouldPause) {
                  console.log('[AgentLoop] Pausing for approval in mode:', executionMode,
                    `(high-risk: ${highRiskCalls.length}, low-risk: ${lowRiskCalls.length})`);

                  // ✅ 虚拟拆分架构：resumption模式下跳过审批检测
                  // 工具已在批准后执行，不应重复暂停
                  if (options?.isResumption) {
                    console.log('[AgentLoop] Resumption: Skipping approval (tools already executed), continuing loop');
                    shouldPause = false;  // 重置标记，继续执行
                  }
                }

                // 仅在非resumption或手动拒绝时才真正暂停
                if (shouldPause) {
                  // ✅ Semi模式下：先执行低风险工具，再对高风险工具请求审批
                  if (executionMode === 'semi' && lowRiskCalls.length > 0) {
                    console.log('[AgentLoop] Semi-mode: Auto-executing', lowRiskCalls.length, 'low-risk tools first');
                    await get().executeTools(sessionId, lowRiskCalls, currentAssistantMsgId);
                  }

                  // 确定需要审批的工具列表
                  const toolsNeedingApproval = executionMode === 'manual' ? toolCalls : highRiskCalls;

                  // 🔑 CRITICAL: 提取待审批工具的 ID，供恢复时使用
                  const pendingApprovalToolIds = toolsNeedingApproval.map(tc => tc.id || `pending_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`);

                  // Save request - 只显示需要审批的工具
                  get().setApprovalRequest(sessionId, {
                    toolName: toolsNeedingApproval.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                    args: toolsNeedingApproval.map(tc => (tc as any).arguments || (tc as any).function?.arguments),
                    reason: `Action requires approval in ${executionMode} mode.`
                  });
                  get().setLoopStatus(sessionId, 'waiting_for_approval');

                  const interventionStep: any = {
                    id: `int_${Date.now()}`,
                    type: 'intervention_required',
                    toolName: toolsNeedingApproval.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                    timestamp: Date.now()
                  };

                  // ✅ CRITICAL FIX: 保存完整的 tool_calls（包括已执行和待审批的）
                  // 同时标记哪些工具待审批，供恢复时使用
                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    { thought_signature: turnThoughtSignature || targetMsg.thought_signature, tool_calls: toolCalls, executionSteps: [...(targetMsg.executionSteps || []), interventionStep], pendingApprovalToolIds }
                  );

                  break; // Auto-exit to wait for user action
                }

                // 🆕 Phase 4: 死循环检测
                // 计算当前工具调用签名（工具名+参数的 JSON 哈希）
                const currentToolCallSignature = toolCalls
                  .map(tc => `${(tc as any).name || (tc as any).function?.name}:${JSON.stringify(tc.arguments || (tc as any).function?.arguments || {})}`)
                  .sort()
                  .join('|');

                if (currentToolCallSignature === lastToolCallSignature) {
                  consecutiveSameActionCount++;
                  console.log(`[AgentLoop] Same action detected (${consecutiveSameActionCount}/${MAX_CONSECUTIVE_SAME_ACTIONS})`);

                  if (consecutiveSameActionCount >= MAX_CONSECUTIVE_SAME_ACTIONS) {
                    console.warn('[AgentLoop] Dead loop detected! Breaking out.');

                    // 记录到 Timeline
                    const deadLoopStep: ExecutionStep = {
                      id: `deadloop_${Date.now()}`,
                      type: 'error',
                      content: `检测到死循环：连续 ${MAX_CONSECUTIVE_SAME_ACTIONS} 轮执行相同的工具调用。自动中断。`,
                      timestamp: Date.now()
                    };
                    updateSteps(deadLoopStep);

                    // 更新消息内容
                    accumulatedContent += '\n\n⚠️ 检测到死循环，已自动中断任务。';
                    get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, { tokens: accumulatedUsage });
                    break;
                  }
                } else {
                  // 签名变化，重置计数器
                  consecutiveSameActionCount = 1;
                  lastToolCallSignature = currentToolCallSignature;
                }

                //  Proceed to Execute
                await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);

                // 🚨 逻辑修复：如果工具执行导致会话暂停（如 ask_user），立即中断循环
                // 防止模型针对"Task Paused"结果生成多余的回复
                if (get().getSession(sessionId)?.loopStatus === 'paused') {
                  console.log('[AgentLoop] Loop paused by tool execution (Auto Mode)');

                  // 确保工具调用的记录已保存
                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    { thought_signature: turnThoughtSignature || targetMsg.thought_signature, tool_calls: toolCalls }
                  );
                  break;
                }

                // 🔑 时序修复：先保存 tool_calls 到 Session，再重新拼装历史
                // 这样拼装时就能获取到完整的数据
                get().updateMessageContent(
                  sessionId,
                  currentAssistantMsgId,
                  accumulatedContent,
                  { thought_signature: turnThoughtSignature || targetMsg.thought_signature, tool_calls: toolCalls }
                );

                // 🔑 关键修复：虚拟拆分新增的assistant+tool（不重新提取整个session）
                const latestSession = get().getSession(sessionId);
                if (latestSession) {
                  // 🔥 只提取当前Turn新增的assistant+tool
                  // 因为currentMessages已经包含了之前的历史
                  const currentAssistantIdx = latestSession.messages.findIndex(m =>
                    m.id === currentAssistantMsgId
                  );

                  if (currentAssistantIdx > -1) {
                    // 提取当前assistant及其后的tool消息
                    console.log('[AgentLoop] latestSession messages count:', latestSession.messages.length, 'idx:', currentAssistantIdx);
                    const newSegment = latestSession.messages.slice(currentAssistantIdx);

                    console.log('[AgentLoop] Extracting new segment (normal branch):', {
                      currentAssistantIdx,
                      newSegmentLength: newSegment.length,
                      roles: newSegment.map(m => m.role).join(' -> ')
                    });

                    // ✅ 虚拟拆分新增的segment
                    const virtualNewSegment = virtualSplitAssistantToolPairs(
                      newSegment,
                      parser,
                      accumulatedContent,
                      allowedSkillIds
                    );

                    // ✅ 追加到currentMessages（不是替换）
                    currentMessages = [...currentMessages, ...virtualNewSegment];
                    console.log('[AgentLoop] Appended new segment after tool execution:', {
                      total: currentMessages.length,
                      newAssistantCount: virtualNewSegment.filter(m => m.role === 'assistant').length,
                      newToolCount: virtualNewSegment.filter(m => m.role === 'tool').length
                    });
                  }
                }

                accumulatedContent += '\n\n';

              } else {
                console.log('[AgentLoop] Final answer received. Stopping.');
                break; // Stop loop directly
              }


            }

            // =====================================================================================
            // Phase 5: Post-Processing
            // =====================================================================================

            // ✅ 关键逻辑：如果是因为等待审批而退出的循环，不要执行清理逻辑
            if (get().getSession(sessionId)?.loopStatus === 'waiting_for_approval') {
              console.log('[AgentLoop] Loop paused for approval/continuation. Skipping cleanup.');
              return;
            }

            // ✅ 关键修复：AgentLoop结束后重置状态，允许新消息
            console.log('[AgentLoop] Loop ended, resetting status to idle');
            get().setLoopStatus(sessionId, 'idle');
            get().setApprovalRequest(sessionId, undefined);  // 清除任何残留的审批请求

            // 🔑 关键修复：先释放 UI 生成状态，再执行后处理
            // 后处理（RAG归档/摘要生成）可能因网络或DB问题挂起，不应阻塞UI恢复
            // finally 块中的同一逻辑是幂等的，重复执行无副作用
            set((state: ChatState) => ({
              currentGeneratingSessionId:
                state.currentGeneratingSessionId === sessionId ? null : state.currentGeneratingSessionId,
            }));

            // 🔑 最终同步：确保从 Store 中获取最新的内容（包括 ToolExecutor 提升的 final_summary）
            // 否则归档到 RAG 的内容可能是空的或旧的
            const finalMsg = get().getSession(sessionId)?.messages.find(m => m.id === currentAssistantMsgId);
            const finalContent = finalMsg?.content || accumulatedContent;

            // 🔑 强制持久化：确保所有防抖的更新立即写入数据库
            if (get().flushMessageUpdates) {
              get().flushMessageUpdates(sessionId, currentAssistantMsgId);
            }

            // Re-calculate context tokens for stats fallback
            const activeWindowSize = agent.ragConfig?.contextWindow || 10;
            const contextText = contextMsgs.map((m: any) => {
              if (typeof m.content === 'string') return m.content;
              return (m.content as any[]).map((p: any) => (p.type === 'text' ? p.text : '')).join('\n');
            }).join('\n');
            const totalContextTokens = estimateTokens(contextText);

            // 1. RAG Archiving (Phase 4b: 使用 post-processor 子模块)
            // 🛡️ Skip RAG if message has error
            if (finalRagOptions.enableMemory !== false && !finalMsg?.isError) {
              await archiveToRag({
                sessionId,
                assistantMsgId,
                userMsgId: userMsg.id,
                userContent: content,
                assistantContent: finalContent, // ✅ Use finalContent
                agent,
                session,
                ragEnabled: true,
                totalContextTokens,
                modelId,
                getSession: get().getSession,
                updateSession: get().updateSession,
                updateMessageProgress: get().updateMessageProgress,
                setVectorizationStatus: get().setVectorizationStatus,
                setKGExtractionStatus: get().setKGExtractionStatus,
                updateSessionTitle: get().updateSessionTitle,
              });
            }

            // 2. KG Extraction (Phase 4b: 使用 post-processor 子模块)
            if (finalContent.trim() && !finalMsg?.isError) {
              extractKnowledgeGraph({
                sessionId,
                assistantMsgId,
                userMsgId: userMsg.id,
                userContent: content,
                assistantContent: finalContent, // ✅ Use finalContent
                agent,
                session,
                ragEnabled: true,
                totalContextTokens,
                modelId,
                getSession: get().getSession,
                updateSession: get().updateSession,
                updateMessageProgress: get().updateMessageProgress,
                setVectorizationStatus: get().setVectorizationStatus,
                setKGExtractionStatus: get().setKGExtractionStatus,
                updateSessionTitle: get().updateSessionTitle,
              });
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

            // 4. Stats & Title (Delegated to shared PostProcessor for consistency)
            updateStats({
              sessionId,
              assistantMsgId,
              userMsgId: userMsg.id,
              userContent: content,
              assistantContent: finalContent,
              agent,
              session: get().getSession(sessionId) || session,
              ragEnabled: finalRagOptions.enableMemory !== false,
              ragUsage,
              accumulatedUsage,
              totalContextTokens,
              modelId,
              providerId: provider?.id, // 传递供应商ID用于统计分类
              providerName: provider?.name || provider?.type, // 传递供应商显示名称
              getSession: get().getSession,
              updateSession: get().updateSession,
              updateMessageProgress: get().updateMessageProgress,
              setVectorizationStatus: get().setVectorizationStatus,
              setKGExtractionStatus: get().setKGExtractionStatus,
              updateSessionTitle: get().updateSessionTitle,
              generateSessionTitle: get().generateSessionTitle, // ✅ Phase 4b: Support AI-powered title generation
            });

          } catch (e: any) {
            // ✅ 错误处理优化：
            // 1. 不创建新的错误气泡，而是更新当前 assistant 消息
            // 2. 只打印警告日志，不触发红屏崩溃
            console.warn('[ChatStore] Agent loop failed:', e.message || e);

            // ✅ 故障容错：确保 RAG 状态被重置（如果是在检索过程中挂掉的）
            useRagStore.getState().updateProcessingState({ status: 'idle' }, assistantMsgId);

            // 🔑 补全：异常时也必须重置 loopStatus，防止状态泄漏
            // 若异常发生在 loopStatus 被设为 'running' 之后，残留状态会导致 ChatInput 显示异常
            get().setLoopStatus(sessionId, 'idle');
            get().setApprovalRequest(sessionId, undefined);

            // 更新当前 assistant 消息显示错误（而非创建新气泡）
            const errorText = e.message || 'Unknown error';
            get().updateMessageContent(
              sessionId,
              assistantMsgId,
              accumulatedContent,
              { isError: true, errorMessage: errorText }
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





        updateMessageLayout: messageManager.updateMessageLayout,

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
            const prompt = `Based on the conversation content, summarize an extremely concise title (NO MORE THAN 5 Chinese characters or 2 words). DO NOT include quotation marks, "Title:", or any extra text. Output the raw title only.\n\nConversation:\n${recentMessages}`;

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
              const sanitizedTitle = title.substring(0, 15);
              get().updateSessionTitle(sessionId, sanitizedTitle);
              return sanitizedTitle;
            }
          } catch (error) {
            console.error('Failed to generate title:', error);
          }
          return undefined;
        },

        vectorizeMessage: messageManager.vectorizeMessage,


        /**
         * 重新生成消息 - 简化版
         * 
         * 设计决策：只允许对最新的 AI 回复进行重新生成
         * 原因：
         * 1. 避免上下文污染 - 重新生成中间消息会导致后续对话断裂
         * 2. 简化逻辑 - 复用 generateMessage，不重复造轮子
         * 3. 用户体验清晰 - 符合直觉，不会产生歧义
         */
        regenerateMessage: async (sessionId: string, _messageId?: string) => {
          const session = get().getSession(sessionId);
          if (!session || session.messages.length < 2) {
            console.warn('[ChatStore] Cannot regenerate: insufficient messages');
            return;
          }

          // 最后一条必须是 AI 回复
          const lastMsg = session.messages[session.messages.length - 1];
          if (lastMsg.role !== 'assistant') {
            console.warn('[ChatStore] Cannot regenerate: last message is not assistant');
            return;
          }

          // 获取前一条用户消息
          const userMsg = session.messages[session.messages.length - 2];
          if (userMsg.role !== 'user') {
            console.warn('[ChatStore] Cannot regenerate: second last message is not user');
            return;
          }

          // 保存用户消息内容和图片
          const userContent = userMsg.content;
          const userImages = userMsg.images;

          console.log('[ChatStore] Regenerating last message, user content:', userContent.substring(0, 50));

          // 1. 删除最后一条 AI 回复 from DB and State
          // ⚠️ Critical Fix: Must delete from DB to prevent ghost messages on reload
          const { SessionRepository } = await import('../lib/db/session-repository');
          await SessionRepository.deleteMessage(sessionId, lastMsg.id);

          set((state: ChatState) => ({
            sessions: state.sessions.map(s =>
              s.id === sessionId
                ? { ...s, messages: s.messages.slice(0, -1) }
                : s
            ),
          }));

          // 2. 重新生成（复用 generateMessage，跳过创建用户消息）
          await get().generateMessage(sessionId, userContent, {
            images: userImages,
            skipUserMessage: true, // ✅ 避免创建重复用户消息
          });
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
      };
    },
    {
      name: 'chat-storage',
      storage: createJSONStorage(() => AsyncStorage),
      // 🔑 Phase 4b: sessions 现在存储在 SQLite 中，不再持久化到 AsyncStorage
      // 保留其他状态（如 activeRequests）的持久化能力以备后用
      partialize: (state) => ({}), // 暂时不持久化任何内容到 AsyncStorage
    }
  )
);
