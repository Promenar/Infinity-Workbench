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
      isResumption?: boolean; // ✅ Added for Steerable Loop
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
    thought_signature?: string, // 🧠 Added for Gemini 2.0
    planningTask?: TaskState, // ✅ Added for Message-Scoped Tasks
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
  dismissActiveTask: (sessionId: string) => void;

  // Steerable Agent Loop Actions
  setExecutionMode: (sessionId: string, mode: 'auto' | 'semi' | 'manual') => void;
  setLoopStatus: (sessionId: string, status: 'running' | 'paused' | 'waiting_for_approval' | 'completed') => void;
  setPendingIntervention: (sessionId: string, intervention: string | undefined) => void;
  setApprovalRequest: (sessionId: string, request: { toolName: string; args: any; reason: string } | undefined) => void;
  // Internal Helper (exposed for loop and resume)
  executeTools: (sessionId: string, toolCalls: ToolCall[], targetMessageId?: string) => Promise<void>;
  resumeGeneration: (sessionId: string, approved?: boolean, intervention?: string) => Promise<void>;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get): ChatState => ({
      sessions: [],
      activeRequests: {},
      activeKGExtractions: {},
      currentGeneratingSessionId: null,

      addSession: (session) => set((state) => ({
        sessions: [{
          ...session,
          executionMode: session.executionMode || 'auto',
          loopStatus: session.loopStatus || 'completed',
        }, ...state.sessions]
      })),
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
        thought_signature?: string, // 🧠 Added for Gemini 2.0
        planningTask?: TaskState, // ✅ Added for Message-Scoped Tasks
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
                        thought_signature: thought_signature !== undefined ? thought_signature : m.thought_signature,
                        planningTask: planningTask || m.planningTask, // ✅ Update planningTask
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

      dismissActiveTask: (sessionId) => {
        set((state) => ({
          sessions: state.sessions.map((s) =>
            s.id === sessionId ? { ...s, activeTask: undefined } : s
          ),
        }));
      },

      setExecutionMode: (sessionId, mode) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, executionMode: mode } : s)),
        })),

      setLoopStatus: (sessionId, status) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, loopStatus: status } : s)),
        })),

      setPendingIntervention: (sessionId, intervention) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, pendingIntervention: intervention } : s)),
        })),

      setApprovalRequest: (sessionId, request) =>
        set((state) => ({
          sessions: state.sessions.map((s) => (s.id === sessionId ? { ...s, approvalRequest: request } : s)),
        })),

      executeTools: async (sessionId: string, toolCalls: ToolCall[], targetMessageId?: string) => {
        const state = get();
        const session = state.getSession(sessionId);
        if (!session) return;
        const agentStore = useAgentStore.getState();
        const agent = agentStore.getAgent(session.agentId);
        if (!agent) return;

        // Verify target message or find last assistant message
        let targetMsgId = targetMessageId;
        if (!targetMsgId) {
          const lastAssistant = [...session.messages].reverse().find(m => m.role === 'assistant');
          if (lastAssistant) targetMsgId = lastAssistant.id;
        }

        if (!targetMsgId) return;

        const targetMsg = session.messages.find(m => m.id === targetMsgId);
        if (!targetMsg) return;

        // 🛡️ 应用级调度防护：防止 OpenAI 兼容模型在流式初期发送空参数导致的崩溃循环
        const hasIncompleteCall = toolCalls.some(tc => {
          const name = (tc as any).name || (tc as any).function?.name;
          if (name === 'manage_task') {
            const args = tc.arguments || (tc as any).function?.arguments || {};
            // 只有当消息处于流式状态且 action 缺失时才拦截
            // 如果已经是最终执行（targetMsg.status 理论上会在 streamChat 结束后由外部判定或此处显式传参）
            // 我们通过检测是否处于流式环境来决定
            return targetMsg.status === 'streaming' && (!args || !args.action);
          }
          return false;
        });

        if (hasIncompleteCall) return;

        // Helper to update execution steps (Restored to fix lint error)
        const updateSteps = (newStep: ExecutionStep) => {
          set(state => {
            const session = state.sessions.find(s => s.id === sessionId);
            if (!session) return {};
            const currentMsg = session.messages.find(m => m.id === targetMsgId);
            if (!currentMsg) return {};

            const currentSteps = currentMsg.executionSteps || [];
            const index = currentSteps.findIndex(s => s.id === newStep.id);
            let updatedSteps = [...currentSteps];

            if (index > -1) {
              updatedSteps[index] = newStep;
            } else {
              updatedSteps.push(newStep);
            }

            return {
              sessions: state.sessions.map(s => s.id === sessionId ? {
                ...s,
                messages: s.messages.map(m => m.id === targetMsgId ? { ...m, executionSteps: updatedSteps } : m)
              } : s)
            };
          });
        };

        for (const tc of toolCalls) {
          if (!tc) continue;
          const tcName = (tc as any).name || (tc as any).function?.name;
          if (!tcName) continue;

          const skill = skillRegistry.getSkill(tcName);
          const stepId = `step_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;

          const tcArgs = (tc as any).arguments || (tc as any).function?.arguments || {};
          let finalArgs = typeof tcArgs === 'string' ? JSON.parse(tcArgs) : tcArgs;

          // 🛡️ 智能参数解包
          if (finalArgs && (finalArgs.parameters || finalArgs.arguments)) {
            const target = finalArgs.parameters || finalArgs.arguments;
            if (typeof target === 'string') {
              try { finalArgs = JSON.parse(target); } catch (e) { console.warn('Param unwrap failed', e); }
            } else if (typeof target === 'object') {
              finalArgs = target;
            }
          }

          updateSteps({
            id: stepId,
            type: 'tool_call',
            toolName: tcName,
            toolArgs: finalArgs,
            toolCallId: tc.id,
            timestamp: Date.now()
          });

          let result: ToolResult;
          try {
            if (skill) {
              result = await skill.execute(finalArgs, { sessionId, agentId: agent.id });
            } else {
              result = { id: (tc as any).id, content: `Error: Skill ${tcName} not found`, status: 'error' };
            }
          } catch (e: any) {
            result = { id: (tc as any).id, content: `Error: ${e.message}`, status: 'error' };
          }

          updateSteps({
            id: `res_${stepId}`,
            type: result.status === 'success' ? 'tool_result' : 'error',
            toolName: tcName,
            toolCallId: tc.id,
            content: result.content,
            data: result.data,
            timestamp: Date.now()
          });

          // 🧐 UI 优化：所有工具执行结果都必须加入历史记录
          get().addMessage(sessionId, {
            id: `tool_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
            role: 'tool',
            tool_call_id: tc.id,
            content: result.content,
            name: tcName,
            createdAt: Date.now(),
            thought_signature: targetMsg.thought_signature // 🔑 Inherit signature from parent assistant msg
          });

          // ✅ 任务状态持久化：如果工具返回了 TaskState 数据 (特别是 manage_task)，
          // 则将其同步回写至触发它的 Assistant 消息中。
          if (result.data && result.status === 'success') {
            const isTaskState = (data: any): data is TaskState =>
              data && typeof data === 'object' && 'steps' in data && 'progress' in data;

            if (isTaskState(result.data)) {
              get().updateMessageContent(
                sessionId,
                targetMsgId,
                targetMsg.content,
                undefined,
                undefined,
                undefined,
                undefined,
                false,
                undefined,
                undefined,
                result.data // Sync task state to message level
              );
            }
          }
        }
      },

      resumeGeneration: async (sessionId, approved = true, intervention) => {
        const session = get().getSession(sessionId);
        if (!session || !session.approvalRequest) return;

        // 0. Update Timeline with Decision
        const lastMsg = session.messages[session.messages.length - 1];
        if (lastMsg && lastMsg.role === 'assistant') {
          const decisionStep: any = {
            id: `dec_${Date.now()}`,
            type: 'intervention_result',
            content: intervention ? `Human Instruction: ${intervention}` : (approved ? 'User Approved' : 'User Rejected'),
            timestamp: Date.now()
          };

          set(state => ({
            sessions: state.sessions.map(s => s.id === sessionId ? {
              ...s,
              messages: s.messages.map(m => m.id === lastMsg.id ? {
                ...m,
                executionSteps: [...(m.executionSteps || []).filter(st => st.type !== 'intervention_required'), decisionStep]
              } : m)
            } : s)
          }));
        }

        if (intervention) {
          get().setPendingIntervention(sessionId, intervention);
        }

        if (!approved && !intervention) {
          // If rejected without instruction, stop loop
          get().setLoopStatus(sessionId, 'paused');
          get().setApprovalRequest(sessionId, undefined);
          return;
        }

        // 1. Execute Tools if approved
        if (approved && !intervention) {
          if (lastMsg && lastMsg.tool_calls) {
            await get().executeTools(sessionId, lastMsg.tool_calls, lastMsg.id);
          }
        }

        // 2. Clear Request & Update Status
        get().setApprovalRequest(sessionId, undefined);
        get().setLoopStatus(sessionId, 'running');

        // 3. Continue Generation (Next Turn)
        await get().generateMessage(sessionId, '', {
          // @ts-ignore
          isResumption: true
        });
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

        if (!options?.isResumption) {
          get().addMessage(sessionId, userMsg);
        } else {
          console.log('[ChatStore] Resumption: Skipping User Message creation');
        }

        set({ currentGeneratingSessionId: sessionId });

        // 3. Assistant Message Handling
        let assistantMsgId: string;
        let accumulatedContent = '';

        if (options?.isResumption) {
          // Reuse last assistant message
          const lastMsg = session.messages[session.messages.length - 1];
          if (lastMsg && lastMsg.role === 'assistant') {
            assistantMsgId = lastMsg.id;
            accumulatedContent = lastMsg.content || '';
            console.log(`[ChatStore] Resumed assistant message ${assistantMsgId}, initial content length: ${accumulatedContent.length}`);
          } else {
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

              // ✅ 后置同步：将检索到的引用数存入 ProcessingHistory 以供指示器显示
              // 使用 'retrieved' 状态而不是 'completed'，以避免在生成期间显示“已归档”
              useRagStore.getState().updateProcessingState({
                status: 'retrieved',
                chunks: ragReferences.map(r => r.content || '')
              }, assistantMsgId);
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
              // ✅ 故障容错：确保检索失败后清理处理状态，防止指示器卡死在检索中
              useRagStore.getState().updateProcessingState({ status: 'idle' }, assistantMsgId);
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
            // 🧠 Generate Detailed Tool Descriptions for ALL models
            const toolsDesc = availableSkills.map(s => {
              let argsDesc = 'No arguments';
              if (s.schema && (s.schema as any).shape) {
                argsDesc = Object.entries((s.schema as any).shape).map(([key, val]: [string, any]) => {
                  // Handle Zod optionality and descriptions
                  const isOptional = val._def?.typeName === 'ZodOptional' || (val.isOptional && typeof val.isOptional === 'function' && val.isOptional());
                  const desc = val.description || (val._def?.description) || '';

                  // Special Handling for Knowledge Base Scope
                  let extraGuidance = '';
                  if (s.id === 'query_vector_db' && key === 'scope') {
                    extraGuidance = ' (CRITICAL: Use "global" ONLY when the user explicitly asks for all documents or global search; use "session" for current context)';
                  }

                  return `  - ${key}${isOptional ? ' (optional)' : ' (REQUIRED)'}: ${desc}${extraGuidance}`;
                }).join('\n');
              }

              return `### ${s.name} (ID: ${s.id})\n${s.description}\nArguments:\n${argsDesc}`;
            }).join('\n\n');


            // 🧠 Unified System Prompt Injection for ALL models
            const toolInstruction = `\n\n[AVAILABLE TOOLS]
You have access to the following skills:

${toolsDesc}

[PLANNING & TASK MANAGEMENT]
If the user's request is complex, multi-step, or requires maintaining state (e.g. "Research and generate a summary"), you MUST use the \`manage_task\` tool.
- CREATE a plan BEFORE execution: \`manage_task({ action: 'create', title: '...', steps: [...] })\`
- UPDATE steps as you finish them: \`manage_task({ action: 'update', steps: [{ id: '...', status: 'completed' }] })\`
- COMPLETE the task when fully done: \`manage_task({ action: 'complete' })\`

🛑 CRITICAL: DO NOT use plain text to list your plan. ALWAYS use \`manage_task\` to keep the user informed.

[EXECUTION RULES]
1. NATIVE TOOL CALLS ONLY. Use the JSON schema provided.
2. 🚫 NO PARAMETER WRAPPING: DO NOT wrap arguments in a "parameters" or "arguments" key. Example: Use \`manage_task({ "action": "create", ... })\`, NOT \`manage_task({ "parameters": { "action": "create", ... } })\`.
3. 🚫 NO INTRODUCTORY TEXT: DO NOT say "I will now search..." or "Here is the plan...". Your response must ONLY contain tool calls.
4. PROVIDE ALL REQUIRED PARAMETERS. For 'query_vector_db', ensures 'query' is NOT empty.
5. Trigger tools immediately. Any leading/trailing conversational text is an ERROR.`;

            finalSystemPrompt += toolInstruction;

            // 🆕 Task State Injection: Provide current task status to the model
            const currentSession = get().getSession(sessionId);
            if (currentSession?.activeTask) {
              const task = currentSession.activeTask;
              const formattedSteps = task.steps.map((s, idx) =>
                `${idx + 1}. [${s.status.toUpperCase()}] ${s.title}${s.description ? ` (${s.description})` : ''}`
              ).join('\n');

              const taskContext = `\n\n[CURRENT TASK STATUS]
Title: ${task.title}
Status: ${task.status}
Progress: ${task.progress}%
Steps:
${formattedSteps}
 
IMPORTANT: You are currently working on this task. Use 'manage_task' to update the status of steps as you complete them.`;
              finalSystemPrompt += taskContext;
            }
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
            isMerged: apiMessage.content.includes('You are NeuralFlow')
          });
          contextMsgs.push({ role: 'user', content: await formatContent(apiMessage.content, apiMessage.images) });

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
            accumulatedContent: string
          ): any[] => {
            const virtualSegment: any[] = [];
            let isFirstAssistant = true;

            for (const m of rawSegment) {
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
                messages: s.messages.map(m => m.id === currentAssistantMsgId ? { ...m, executionSteps: updatedSteps } : m)
              } : s)
            }));
          };


          while (loopCount < MAX_LOOP_COUNT) {
            loopCount++;
            console.log(`[AgentLoop] Turn ${loopCount}/${MAX_LOOP_COUNT}`);

            // Steerable Agent Loop: Intervention
            const pending = get().getSession(sessionId)?.pendingIntervention;
            if (pending) {
              console.log('[AgentLoop] Injecting User Intervention:', pending);
              currentMessages.push({ role: 'system' as any, content: `[IMMEDIATE USER INTERVENTION]: ${pending}` });
              get().setPendingIntervention(sessionId, undefined);
            }


            // 4. Get Enabled Skills
            const availableSkills = skillRegistry.getEnabledSkills();
            console.log('[AgentLoop] Available Skills:', availableSkills.map(s => s.id));


            // 5. Stream Chat
            let toolCalls: ToolCall[] | undefined;
            let reasoningFromThisTurn = '';

            // 🧠 Optimization: Use Array Buffer for Content to reduce GC pressure
            let contentBuffer: string[] = [];
            let turnContent = ''; // RE-ADDED: Sync with contentBuffer for history persistence
            let turnThoughtSignature = '';

            // 🧠 StreamParser for incremental parsing
            const parser = new StreamParser(provider.type as any);
            let planParsed = false;

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
                  reasoningFromThisTurn += parseResult.reasoning;
                }
                if (token.reasoning) {
                  reasoningFromThisTurn += token.reasoning;
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
                      undefined,
                      undefined,
                      undefined,
                      undefined,
                      false,
                      undefined,
                      undefined,
                      newActiveTask // Pass planning task
                    );

                    // ✅ Sync to Message Level immediately
                    get().updateMessageContent(
                      sessionId,
                      currentAssistantMsgId,
                      accumulatedContent,
                      undefined,
                      undefined,
                      undefined,
                      undefined,
                      false,
                      undefined,
                      undefined,
                      newActiveTask // Pass planning task
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
                    token.usage
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

                  // Clear bubble reasoning if we show it in timeline
                  get().updateMessageContent(
                    sessionId,
                    currentAssistantMsgId,
                    accumulatedContent,
                    token.usage,
                    ''
                  );
                  lastTimelineUpdateTime = now;
                }
              },
              (error) => { console.warn('Stream error', error); },
              {
                skills: availableSkills,
                reasoning: true,
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

            // Flush remaining content buffer if any
            if (contentBuffer.length > 0) {
              const tailContent = contentBuffer.join('');
              accumulatedContent += tailContent;
              turnContent += tailContent;
              contentBuffer = [];
              get().updateMessageContent(
                sessionId,
                currentAssistantMsgId,
                accumulatedContent,
                accumulatedUsage
              );
            }

            // Flush final reasoning to timeline
            if (reasoningFromThisTurn) {
              const stepId = `think_turn_${loopCount}`;
              updateSteps({
                id: stepId,
                type: 'thinking',
                content: reasoningFromThisTurn,
                timestamp: Date.now()
              });
            }

            if (reasoningFromThisTurn || turnThoughtSignature) {
              get().updateMessageContent(
                sessionId,
                currentAssistantMsgId,
                finalDisplayContent,
                accumulatedUsage,
                reasoningFromThisTurn,
                undefined,
                undefined,
                undefined,
                undefined,
                turnThoughtSignature
              );
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

                  // Parse key-value parameters: <parameter><key>x</key><value>y</value></parameter>
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
                console.log(`[AgentLoop] Fallback Parser found ${extractedCalls.length} calls:`, extractedCalls.map(c => c.name));
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
              get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent, accumulatedUsage, reasoningFromThisTurn);
            }

            if (toolCalls && toolCalls.length > 0) {
              // 1. Plan/Task Detection & Extraction (Legacy support provided by Parser, checking content strip only)
              // Parser already detected logic, but maybe we need to strip legacy <plan> tags if they remain?
              // The parser strips them from 'content' but accumulatedContent might differ? 
              // My parser returns CLEAN content. So detecting <plan> in turnContent is redundant if using parser output.
              // BUT: AccumulatedContent logic in onToken: accumulatedContent += parseResult.content (which IS parsed/clean).
              // So we don't need to strip <plan> here.

              // 2. 历史上下文同步已由后续的动态重组逻辑（1872-1890行）处理
              // 不再在此处 push，以防止重复消息导致 API 错误
              console.log(`[AgentLoop] End of Turn ${loopCount} - Tools detected:`, toolCalls.length);

              // 🔑 检测 manage_task complete（任务完成，退出循环）
              const isTaskComplete = toolCalls.some(tc =>
                tc.name === 'manage_task' && tc.arguments?.action === 'complete'
              );

              if (isTaskComplete) {
                console.log('[AgentLoop] Task marked as complete, executing final update and stopping');
                // 执行complete调用以更新任务状态，然后立即退出
                await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);
                break; // 任务完成，退出循环
              }

              // 🔑 检测 manage_task create（任务创建，必须继续执行）
              // DeepSeek已知问题：工具调用时content可能为空，导致循环误判为final answer
              // 解决方案：检测到任务创建后，强制继续循环
              const isTaskCreate = toolCalls.some(tc =>
                tc.name === 'manage_task' && tc.arguments?.action === 'create'
              );

              if (isTaskCreate) {
                console.log('[AgentLoop] Task created, will execute and rebuild messages before continuing');
                await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);

                // 🔑 关键修复：重构消息历史（包含tool results）再continue
                // DeepSeek是无状态API，必须传递完整历史
                set(state => ({
                  sessions: state.sessions.map(s => s.id === sessionId ? {
                    ...s,
                    messages: s.messages.map(m => m.id === currentAssistantMsgId ? {
                      ...m,
                      tool_calls: toolCalls,
                      thought_signature: turnThoughtSignature || m.thought_signature,
                      // 🔑 CRITICAL: 保留reasoning字段，不要覆盖（Turn 1已通过updateMessageContent保存）
                      reasoning: m.reasoning  // 保留原有reasoning
                    } : m)
                  } : s)
                }));

                // 🔑 关键修复：虚拟拆分assistant+tool序列（符合OpenAI API规范）
                const latestSession = get().getSession(sessionId);
                if (latestSession) {
                  const baseHistory = [...contextMsgs];

                  const userMsgIdx = latestSession.messages.findIndex(m =>
                    m.role === 'user' && m.content === content
                  );

                  if (userMsgIdx > -1) {
                    // 提取user消息之后的所有消息
                    const rawSegment = latestSession.messages.slice(userMsgIdx + 1);

                    // ✅ 应用虚拟拆分逻辑
                    const virtualSegment = virtualSplitAssistantToolPairs(
                      rawSegment,
                      parser,
                      accumulatedContent
                    );

                    currentMessages = [...baseHistory, ...virtualSegment];
                    console.log('[AgentLoop] Rebuilt messages after task create (virtual split):', {
                      total: currentMessages.length,
                      virtualAssistantCount: virtualSegment.filter(m => m.role === 'assistant').length,
                      toolCount: virtualSegment.filter(m => m.role === 'tool').length
                    });
                  }
                }

                loopCount++;
                if (loopCount >= MAX_LOOP_COUNT) {
                  console.log('[AgentLoop] Max loop count reached after task create');
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
              if (executionMode === 'manual') shouldPause = true;
              else if (executionMode === 'semi') {
                // Check high risk
                if (toolCalls.some(tc => {
                  const name = (tc as any).name || (tc as any).function?.name || '';
                  return ['write_to_file', 'run_command', 'replace_file_content', 'multi_replace_file_content'].some(risk => name.includes(risk));
                })) shouldPause = true;
              }

              if (shouldPause) {
                console.log('[AgentLoop] Pausing for approval in mode:', executionMode);
                const currentSession = get().getSession(sessionId);

                // 🔑 协作反馈优化：如果 content 为空（模型只调用了工具），注入占位提示语
                // 以便在 ChatBubble 中看起来更连贯
                if (!accumulatedContent.trim()) {
                  accumulatedContent = "I've planned some actions that require your approval before I proceed.";
                  get().updateMessageContent(sessionId, currentAssistantMsgId, accumulatedContent);
                }

                // Save request
                get().setApprovalRequest(sessionId, {
                  toolName: toolCalls.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                  args: toolCalls.map(tc => (tc as any).arguments || (tc as any).function?.arguments),
                  reason: `Action requires approval in ${executionMode} mode.`
                });
                get().setLoopStatus(sessionId, 'waiting_for_approval');
                // Add intervention required step to timeline
                const interventionStep: any = {
                  id: `int_${Date.now()}`,
                  type: 'intervention_required',
                  toolName: toolCalls.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                  timestamp: Date.now()
                };

                // Save results to store but DO NOT execute
                set(state => ({
                  sessions: state.sessions.map(s => s.id === sessionId ? {
                    ...s,
                    messages: s.messages.map(m => m.id === currentAssistantMsgId ? {
                      ...m,
                      content: accumulatedContent,
                      tool_calls: toolCalls,
                      thought_signature: turnThoughtSignature || m.thought_signature,
                      executionSteps: [...(m.executionSteps || []), interventionStep]
                    } : m)
                  } : s)
                }));
                return; // Break loop and function
              }

              //  Proceed to Execute
              await get().executeTools(sessionId, toolCalls, currentAssistantMsgId);

              // 🔑 时序修复：先保存 tool_calls 到 Session，再重新拼装历史
              // 这样拼装时就能获取到完整的数据
              set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? {
                  ...s,
                  messages: s.messages.map(m => m.id === currentAssistantMsgId ? {
                    ...m,
                    tool_calls: toolCalls,
                    thought_signature: turnThoughtSignature || m.thought_signature,
                    // 🔑 CRITICAL: 保留reasoning字段，不要覆盖（与isTaskCreate分支保持一致）
                    reasoning: m.reasoning  // 保留原有reasoning
                  } : m)
                } : s)
              }));

              // 🔑 关键修复：虚拟拆分assistant+tool序列（符合OpenAI API规范）
              const latestSession = get().getSession(sessionId);
              if (latestSession) {
                const baseHistory = [...contextMsgs];

                const userMsgIdx = latestSession.messages.findIndex(m =>
                  m.role === 'user' && m.content === content
                );

                if (userMsgIdx > -1) {
                  // 提取user消息之后的所有消息
                  const rawSegment = latestSession.messages.slice(userMsgIdx + 1);

                  // ✅ 应用虚拟拆分逻辑
                  const virtualSegment = virtualSplitAssistantToolPairs(
                    rawSegment,
                    parser,
                    accumulatedContent
                  );

                  currentMessages = [...baseHistory, ...virtualSegment];
                  console.log('[AgentLoop] Rebuilt messages after tool execution (virtual split):', {
                    total: currentMessages.length,
                    virtualAssistantCount: virtualSegment.filter(m => m.role === 'assistant').length,
                    toolCount: virtualSegment.filter(m => m.role === 'tool').length
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
              updateProcessingState({
                sessionId,
                status: 'chunking',
                subStage: 'EMBEDDING', // 归档阶段主要也是在计算向量
                progress: 50,
                pulseActive: true,
                startTime: archiveStartTime,
                chunks: []
              }, assistantMsgId);
              await new Promise((resolve) => setTimeout(resolve, 0));

              await MemoryManager.addTurnToMemory(sessionId, content, accumulatedContent, userMsg.id, assistantMsgId);

              const elapsed = Date.now() - archiveStartTime;
              if (elapsed < 800) await new Promise((resolve) => setTimeout(resolve, 800 - elapsed));

              updateProcessingState({
                sessionId,
                status: 'archived',
                subStage: undefined,
                progress: 100,
                pulseActive: false,
                chunks: []
              }, assistantMsgId);
              get().setVectorizationStatus(sessionId, [userMsg.id, assistantMsgId], 'success');
            } catch (e) {
              console.error('[RAG] Archive failed:', e);
              get().setVectorizationStatus(sessionId, [userMsg.id, assistantMsgId], 'error');
              const { updateProcessingState } = await import('../store/rag-store').then((m) => m.useRagStore.getState());
              updateProcessingState({ sessionId, status: 'error' }, assistantMsgId);
            }
          }

          // 2. KG Extraction (Bi-directional learning after final settle)
          if (accumulatedContent.trim()) {
            setTimeout(async () => {
              try {
                const { useSettingsStore } = require('../store/settings-store');
                const globalConfig = useSettingsStore.getState().globalRagConfig;
                const session = get().getSession(sessionId);
                if (!session) return;

                const isSuperAssistant = sessionId === 'super_assistant';
                const sessionKgOption = session.ragOptions?.enableKnowledgeGraph;
                const isKgEnabled = sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph;

                if (!isKgEnabled) return;

                const costStrategy = agent?.ragConfig?.costStrategy || globalConfig.costStrategy || 'summary-first';
                if (costStrategy === 'on-demand' && !isSuperAssistant) return;

                get().setKGExtractionStatus(sessionId, true);

                // 🔑 核心修正：同步到消息持久化字段，防止流程结束后消失
                get().updateMessageProgress(sessionId, assistantMsgId, {
                  stage: 'searching',
                  percentage: 10,
                  message: '全域知识同步：实体识别...'
                });

                // 🔑 Combined Extraction: Include both User question and Assistant answer for relationship context
                const combinedText = `User: ${content}\nAssistant: ${accumulatedContent}`;

                await graphExtractor.extractAndSave(combinedText, undefined, {
                  sessionId,
                  agentId: session.agentId,
                  messageId: assistantMsgId
                });

                // 🔑 最终收尾：同步“完成”状态到消息持久化字段
                get().updateMessageProgress(sessionId, assistantMsgId, {
                  stage: 'done',
                  percentage: 100,
                  message: '知识同步完成'
                });
              } catch (e) {
                console.warn('[ChatStore] AI Response KG extraction failed:', e);
              } finally {
                get().setKGExtractionStatus(sessionId, false);
              }
            }, 800); // 🚨 Increased delay slightly to ensure UI settles from any tool execution
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
                planningTask: undefined, // ✅ Reset task state
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
                onProgress: (stage: string, percentage: number, subStage?: string, networkStats?: any) => {
                  const { updateProcessingState } = require('../store/rag-store').useRagStore.getState();
                  updateProcessingState({
                    sessionId,
                    stage: stage as any,
                    progress: percentage,
                    subStage,
                    networkStats
                  }, messageId);
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

          // 🆕 Task State Injection: Provide current task status to the model
          const currentSessionForTask = get().getSession(sessionId);
          if (currentSessionForTask?.activeTask) {
            const task = currentSessionForTask.activeTask;
            const formattedSteps = task.steps.map((s, idx) =>
              `${idx + 1}. [${s.status.toUpperCase()}] ${s.title}${s.description ? ` (${s.description})` : ''}`
            ).join('\n');

            const taskContext = `\n\n[CURRENT TASK STATUS]
Title: ${task.title}
Status: ${task.status}
Progress: ${task.progress}%
Steps:
${formattedSteps}

IMPORTANT: You are currently working on this task. Use 'manage_task' to update the status of steps as you complete them.`;
            finalSystemPrompt += taskContext;
          }

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

          // Sanitized History Generation matches generateMessage logic
          const history = await (async () => {
            const historyMsgs = session.messages.slice(0, msgIndex).slice(-activeWindowSize);
            const sanitized: any[] = [];

            for (let i = 0; i < historyMsgs.length; i++) {
              const msg = historyMsgs[i];
              if (msg.role === 'system') continue;

              const apiMsg: any = {
                role: msg.role,
                content: await formatContent(msg.content, msg.images),
                name: msg.name,
                reasoning: msg.reasoning,
                thought_signature: msg.thought_signature,
              };

              // Sanitize Tool Calls
              if (msg.role === 'assistant' && msg.tool_calls && msg.tool_calls.length > 0) {
                // Rule 1: Missing thought_signature on Thinking Model -> Strip Tool Calls (Legacy Fallback)
                // (Pragmatic fix: if we don't have it, we can't send it, so convert to text-only)
                if (!msg.thought_signature && ((modelConfig as any)?.capabilities?.thinking || (get().activeRequests[sessionId] as any)?.model?.includes('thinking'))) {
                  console.warn('[Regenerate] Stripping legacy tool calls due to missing thought_signature');
                  apiMsg.tool_calls = undefined;
                } else {
                  // Rule 2: Hanging Tool Calls (DeepSeek/Strict)
                  const toolCallIds = new Set(msg.tool_calls.map((tc: any) => tc.id));
                  const answeredIds = new Set<string>();
                  for (let j = i + 1; j < historyMsgs.length; j++) {
                    const ahead = historyMsgs[j];
                    if (ahead.role === 'tool' && ahead.tool_call_id && toolCallIds.has(ahead.tool_call_id)) {
                      answeredIds.add(ahead.tool_call_id);
                    }
                    if (ahead.role === 'user' || (ahead.role === 'assistant' && ahead.tool_calls)) break;
                  }

                  if (answeredIds.size > 0 && answeredIds.size === toolCallIds.size) {
                    apiMsg.tool_calls = msg.tool_calls;
                  } else {
                    apiMsg.tool_calls = undefined; // Strip incomplete
                  }
                }
              }

              // Sanitize Tool Results
              if (msg.role === 'tool') {
                const hasAssistant = sanitized.some(m => m.role === 'assistant' && m.tool_calls?.some((tc: any) => tc.id === msg.tool_call_id));
                if (hasAssistant) {
                  apiMsg.tool_call_id = msg.tool_call_id;
                  apiMsg.name = msg.name;
                } else {
                  continue; // Skip orphan
                }
              }

              sanitized.push(apiMsg);
            }
            return sanitized;
          })();

          contextMsgs = [...contextMsgs, ...history] as any;

          // 7. Loop Setup
          let loopCount = 0;
          const MAX_LOOP_COUNT = 5;
          let currentMessages = [...contextMsgs];
          let loopExecutionSteps: ExecutionStep[] = [];

          let accumulatedContent = '';
          let accumulatedReasoning = '';
          let accumulatedCitations = initialCitations;
          let accumulatedUsage: any;

          // 🔑 关键修复: 显示指示活跃重生成消息
          const { updateProcessingState } = require('../store/rag-store').useRagStore.getState();
          updateProcessingState({ sessionId, pulseActive: true }, messageId);

          while (loopCount < MAX_LOOP_COUNT) {
            loopCount++;
            console.log(`[AgentLoop] Regenerate Turn ${loopCount}/${MAX_LOOP_COUNT}`);

            const availableSkills = skillRegistry.getEnabledSkills();

            let turnContent = '';
            let toolCalls: any[] | undefined;
            let reasoningFromThisTurn = '';
            let turnThoughtSignature: string | undefined;

            await client.streamChat(
              ContextManager.trimContext(currentMessages as any, activeWindowSize),
              (token) => {
                const currentState = get();
                if (!currentState.activeRequests[sessionId]) return;

                if (token.usage) accumulatedUsage = token.usage;

                if (token.thought_signature) {
                  turnThoughtSignature = token.thought_signature;
                }

                if (token.content) {
                  turnContent += token.content;
                  accumulatedContent += token.content;

                  // Capture thinking if interleaved
                  const thinkMatch = turnContent.match(/<(?:thought|think)>([\s\S]*?)<\/(?:thought|think)>/i) ||
                    turnContent.match(/Thought:([\s\S]*?)(?:\n\n|\n|$)/i);
                  if (thinkMatch) {
                    reasoningFromThisTurn = thinkMatch[1].trim();
                    turnContent = turnContent.replace(thinkMatch[0], '').trim();
                    accumulatedContent = accumulatedContent.replace(thinkMatch[0], '').trim();
                  }
                }

                if (token.reasoning) {
                  accumulatedReasoning += token.reasoning;
                  reasoningFromThisTurn += token.reasoning;
                }

                if (token.citations) accumulatedCitations = token.citations;

                if (token.toolCalls) {
                  toolCalls = token.toolCalls;
                }

                get().updateMessageContent(
                  sessionId,
                  messageId,
                  accumulatedContent,
                  undefined,
                  accumulatedReasoning,
                  accumulatedCitations,
                  ragReferences,
                  false,
                  undefined,
                  turnThoughtSignature
                );
              },
              (error) => console.warn('Regenerate error', error),
              { ...options, skills: availableSkills }
            );

            if (!toolCalls || toolCalls.length === 0) break;

            // Handle Tool Calls
            // Steerable Agent Loop: Check Approval
            const executionMode = session.executionMode || 'auto';
            let shouldPause = false;
            if (executionMode === 'manual') shouldPause = true;
            else if (executionMode === 'semi') {
              // Check high risk
              if (toolCalls.some(tc => {
                const name = (tc as any).name || (tc as any).function?.name || '';
                return ['write_to_file', 'run_command', 'replace_file_content', 'multi_replace_file_content'].some(risk => name.includes(risk));
              })) shouldPause = true;
            }

            if (shouldPause) {
              console.log('[AgentLoop] Regenerate: Pausing for approval in mode:', executionMode);

              // 🔑 Feedback Injection: If content is empty while pausing, inject a friendly message
              if (!accumulatedContent.trim()) {
                accumulatedContent = "I've planned some actions that require your approval before I proceed.";
                get().updateMessageContent(sessionId, messageId, accumulatedContent);
              }

              // Save request
              get().setApprovalRequest(sessionId, {
                toolName: toolCalls.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                args: toolCalls.map(tc => (tc as any).arguments || (tc as any).function?.arguments),
                reason: `Action requires approval in ${executionMode} mode.`
              });
              get().setLoopStatus(sessionId, 'waiting_for_approval');

              // Add intervention required step to timeline
              const interventionStep: any = {
                id: `int_${Date.now()}`,
                type: 'intervention_required',
                toolName: toolCalls.map(tc => (tc as any).name || (tc as any).function?.name).join(', '),
                timestamp: Date.now()
              };

              // Save results to message but break loop
              set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? {
                  ...s,
                  messages: s.messages.map(m => m.id === messageId ? {
                    ...m,
                    content: accumulatedContent,
                    tool_calls: toolCalls,
                    executionSteps: [...(m.executionSteps || []), interventionStep]
                  } : m)
                } : s)
              }));
              return;
            }

            currentMessages.push({
              role: 'assistant',
              content: turnContent, // Keep raw content (StreamParser handles cleaning for display, but here we might need consistent behavior)
              tool_calls: toolCalls,
              reasoning: reasoningFromThisTurn,
              thought_signature: turnThoughtSignature
            } as any);

            for (const tool of toolCalls) {
              if (!tool) continue; // 🛡️ CRITICAL: Prevent crash if GLM outputs a null tool item

              // 🛡️ 安全解析参数：兼容 Native OpenAI 和扁平化格式
              const tcName = tool.name || tool.function?.name;
              let tcArgs: any = {};

              try {
                const rawArgs = tool.arguments || tool.function?.arguments || {};
                const parsed = typeof rawArgs === 'string' ? JSON.parse(rawArgs) : rawArgs;

                // 🛡️ 应用全局参数展平 (Auto-Flattening)
                tcArgs = parsed;
                if (parsed && (parsed.parameters || parsed.arguments)) {
                  const target = parsed.parameters || parsed.arguments;
                  if (typeof target === 'string') {
                    try {
                      tcArgs = JSON.parse(target);
                    } catch (pe) {
                      console.warn('[Regenerate] Failed to unwrap nested string params:', pe);
                    }
                  } else if (typeof target === 'object') {
                    tcArgs = target;
                  }
                }
              } catch (parseErr) {
                console.warn('[Regenerate] Failed to parse tool arguments:', parseErr);
              }

              if (!tcName) continue;

              const skill = skillRegistry.getSkill(tcName);
              let result: ToolResult;

              const stepId = `step_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
              loopExecutionSteps.push({
                id: stepId,
                type: 'tool_call',
                toolName: tcName,
                toolArgs: tcArgs,
                timestamp: Date.now()
              });

              set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? {
                  ...s,
                  messages: s.messages.map(m => m.id === messageId ? { ...m, executionSteps: [...loopExecutionSteps] } : m)
                } : s)
              }));

              if (skill) {
                try {
                  result = await skill.execute(tcArgs, { sessionId, agentId: agent.id });
                } catch (e: any) {
                  result = { id: 'error', content: e.message, status: 'error' };
                }
              } else {
                result = { id: 'error', content: `Skill ${tcName} not found`, status: 'error' };
              }

              const resultStep = {
                id: `res_${stepId}`,
                type: 'tool_result' as const,
                toolName: tcName,
                content: typeof result.content === 'string' ? result.content : JSON.stringify(result.content),
                data: result.data,
                timestamp: Date.now()
              };
              loopExecutionSteps.push(resultStep);

              set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? {
                  ...s,
                  messages: s.messages.map(m => m.id === messageId ? { ...m, executionSteps: [...loopExecutionSteps] } : m)
                } : s)
              }));

              currentMessages.push({
                role: 'tool',
                tool_call_id: tool.id,
                content: typeof result.content === 'string' ? result.content : JSON.stringify(result.content)
              } as any);
            }
          }

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

          // 2. Post-Processing (Archiving & KG)
          // Archiving
          if (session.ragOptions?.enableMemory !== false) {
            const { MemoryManager } = await import('../lib/rag/memory-manager');
            const userMsg = session.messages[msgIndex - 1];
            if (userMsg) {
              await MemoryManager.addTurnToMemory(sessionId, userMsg.content, accumulatedContent, userMsg.id, messageId);
            }
          }

          // KG Extraction
          if (accumulatedContent.trim()) {
            setTimeout(async () => {
              try {
                const { useSettingsStore } = require('../store/settings-store');
                const globalConfig = useSettingsStore.getState().globalRagConfig;
                const activeSession = get().getSession(sessionId);
                if (!activeSession) return;

                const sessionKgOption = activeSession.ragOptions?.enableKnowledgeGraph;
                const isKgEnabled = sessionKgOption !== undefined ? sessionKgOption : globalConfig.enableKnowledgeGraph;

                if (!isKgEnabled) return;

                const userMsg = activeSession.messages[msgIndex - 1];
                const combinedText = userMsg ? `User: ${userMsg.content}\nAssistant: ${accumulatedContent}` : accumulatedContent;

                const { graphExtractor } = await import('../lib/rag/graph-extractor');
                get().setKGExtractionStatus(sessionId, true);
                await graphExtractor.extractAndSave(combinedText, undefined, {
                  sessionId,
                  agentId: activeSession.agentId,
                  messageId: messageId
                });
              } catch (e) {
                console.warn('[ChatStore] Regenerate KG extraction failed:', e);
              } finally {
                get().setKGExtractionStatus(sessionId, false);
              }
            }, 500);
          }

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
