/**
 * 消息管理模块 (🔑 Phase 4b: SQLite 双写模式)
 * 负责消息的创建、更新、删除和向量化
 * 使用防抖机制处理高频更新，同时持久化到 SQLite
 */

import type { ManagerContext, MessageManager } from './types';
import type {
    Message, SessionId, TokenUsage, RagReference, RagProgress, RagMetadata, TaskState,
    InferenceParams
} from '../../types/chat';
import type { ToolCall, ToolResult, ExecutionStep } from '../../types/skills';
import { SessionRepository } from '../../lib/db/session-repository';

// 🔑 防抖写入 SQLite 的间隔（流式更新时）
const DB_DEBOUNCE_MS = 500;

export const createMessageManager = (context: ManagerContext): MessageManager => {
    const { get, set } = context;

    // 🔑 Throttling & Buffer State (for Zustand updates)
    const pendingUpdates = new Map<string, {
        content: string;
        tokens?: TokenUsage;
        reasoning?: string;
        citations?: { title: string; url: string; source?: string }[];
        ragReferences?: RagReference[];
        ragReferencesLoading?: boolean;
        ragMetadata?: RagMetadata;
        thought_signature?: string;
        taskState?: TaskState;
        tool_calls?: ToolCall[];
        executionSteps?: ExecutionStep[];
        pendingApprovalToolIds?: string[];
        toolResults?: { type: 'echarts' | 'mermaid' | 'math' | 'image' | 'text'; content: string; name?: string }[];
        isError?: boolean;
        errorMessage?: string;
        isLongWait?: boolean;
    }>();

    const throttleTimers = new Map<string, NodeJS.Timeout>();

    // 🔑 Phase 4b: DB 防抖写入状态
    const dbPendingUpdates = new Map<string, Partial<Message>>();
    const dbDebounceTimers = new Map<string, NodeJS.Timeout>();

    // 防抖写入 SQLite
    const debouncedDbUpdate = (sessionId: string, messageId: string, updates: Partial<Message>) => {
        const key = `${sessionId}:${messageId}`;

        // 合并更新
        const existing = dbPendingUpdates.get(key) || {};
        dbPendingUpdates.set(key, { ...existing, ...updates });

        // 清除旧定时器
        const oldTimer = dbDebounceTimers.get(key);
        if (oldTimer) clearTimeout(oldTimer);

        // 设置新定时器
        const timer = setTimeout(async () => {
            const pendingUpdate = dbPendingUpdates.get(key);
            if (pendingUpdate) {
                try {
                    await SessionRepository.updateMessage(sessionId, messageId, pendingUpdate);
                } catch (e) {
                    console.warn('[MessageManager] DB update failed:', e);
                }
                dbPendingUpdates.delete(key);
            }
            dbDebounceTimers.delete(key);
        }, DB_DEBOUNCE_MS);

        dbDebounceTimers.set(key, timer);
    };

    const flushUpdate = (sessionId: string, messageId: string) => {
        const key = `${sessionId}:${messageId}`;
        const pending = pendingUpdates.get(key);
        if (!pending) return;

        // Clear buffer and timer
        pendingUpdates.delete(key);
        throttleTimers.delete(key);

        // Perform actual store update
        set((state) => {
            const session = state.sessions.find((s) => s.id === sessionId);
            if (!session) return {};

            const message = session.messages.find((m) => m.id === messageId);
            if (!message) return {};

            // Calculate Token Deltas (Billing) using pending data vs current state
            const oldTokens = message.tokens || { input: 0, output: 0, total: 0 };
            const newTokens = pending.tokens || oldTokens;

            const tokensChanged = newTokens.total !== oldTokens.total;
            const deltaInput = newTokens.input - oldTokens.input;
            const deltaOutput = newTokens.output - oldTokens.output;
            const deltaTotal = newTokens.total - oldTokens.total;

            const currentBilling = session.stats?.billing || {
                chatInput: { count: 0, isEstimated: false },
                chatOutput: { count: 0, isEstimated: false },
                ragSystem: { count: 0, isEstimated: false },
                total: 0,
                costUSD: 0,
            };

            const updatedBilling = { ...currentBilling };

            if (tokensChanged && deltaTotal > 0) {
                if (message.role === 'assistant') {
                    updatedBilling.chatOutput.count += deltaOutput;
                    const ragSystemDelta = deltaTotal - deltaOutput;
                    const hasRag =
                        (pending.ragMetadata !== undefined ? pending.ragMetadata : message.ragMetadata) ||
                        (pending.ragReferences !== undefined ? pending.ragReferences : message.ragReferences);

                    if (hasRag && ragSystemDelta > 0) {
                        updatedBilling.ragSystem.count += ragSystemDelta;
                    } else {
                        updatedBilling.chatInput.count += deltaInput;
                    }
                } else {
                    updatedBilling.chatInput.count += deltaInput;
                }
                updatedBilling.total += deltaTotal;
            }

            // 🔑 Phase 4b: 同时防抖写入 SQLite
            debouncedDbUpdate(sessionId, messageId, {
                content: pending.content,
                tokens: newTokens,
                reasoning: pending.reasoning,
                citations: pending.citations,
                ragReferences: pending.ragReferences,
                ragReferencesLoading: pending.ragReferencesLoading,
                ragMetadata: pending.ragMetadata,
                thought_signature: pending.thought_signature,
                planningTask: pending.taskState,
                executionSteps: pending.executionSteps,
                pendingApprovalToolIds: pending.pendingApprovalToolIds,
                toolResults: pending.toolResults,

                // Phase 4c: DeepSeek Consistency - save tool_calls if pending
                ...(pending.tool_calls && { tool_calls: pending.tool_calls }),
                ...(pending.isError !== undefined && { isError: pending.isError }),
                ...(pending.tool_calls && { tool_calls: pending.tool_calls }),
                ...(pending.isError !== undefined && { isError: pending.isError }),
                ...(pending.errorMessage !== undefined && { errorMessage: pending.errorMessage }),
                ...(pending.isLongWait !== undefined && { isLongWait: pending.isLongWait })
            });

            return {
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        return {
                            ...s,
                            messages: s.messages.map((m) =>
                                m.id === messageId
                                    ? {
                                        ...m,
                                        content: pending.content,
                                        tokens: newTokens,
                                        ...(pending.reasoning !== undefined && { reasoning: pending.reasoning }),
                                        ...(pending.citations !== undefined && { citations: pending.citations }),
                                        ...(pending.ragReferences !== undefined && { ragReferences: pending.ragReferences }),
                                        ...(pending.ragReferencesLoading !== undefined && { ragReferencesLoading: pending.ragReferencesLoading }),
                                        ...(pending.ragMetadata !== undefined && { ragMetadata: pending.ragMetadata }),
                                        ...(pending.thought_signature !== undefined && { thought_signature: pending.thought_signature }),
                                        ...(pending.taskState && { planningTask: pending.taskState }),
                                        ...(pending.tool_calls && { tool_calls: pending.tool_calls }),
                                        ...(pending.executionSteps && { executionSteps: pending.executionSteps }),
                                        ...(pending.pendingApprovalToolIds && { pendingApprovalToolIds: pending.pendingApprovalToolIds }),
                                        ...(pending.toolResults && { toolResults: pending.toolResults }),
                                        ...(pending.toolResults && { toolResults: pending.toolResults }),
                                        ...(pending.isError !== undefined && { isError: pending.isError }),
                                        ...(pending.isError !== undefined && { isError: pending.isError }),
                                        ...(pending.errorMessage !== undefined && { errorMessage: pending.errorMessage }),
                                        ...(pending.isLongWait !== undefined && { isLongWait: pending.isLongWait }),
                                        // 🔑 Phase 4c: Fix Potential Stale Overwrite of Tool Calls
                                        // If pending doesn't have tool_calls, do we keep existing ones? Yes, spread ...m does that.
                                        // But if we are about to overwrite msg with new tool_calls via another setter, we must ensure
                                        // that setter sees the latest state. 'flushMessageUpdates' ensures pendingUpdates are applied.
                                    }
                                    : m
                            ),
                            lastMessage: pending.content,
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
        });
    };

    return {
        addMessage: async (sessionId: string, message: Message) => {
            // 🔑 Phase 4b: 先写 SQLite
            try {
                await SessionRepository.addMessage(sessionId, message);
            } catch (e) {
                console.warn('[MessageManager] DB addMessage failed:', e);
            }

            // 更新 Zustand
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
            thought_signature?: string,
            taskState?: TaskState,
            tool_calls?: ToolCall[],
            executionSteps?: ExecutionStep[],
            pendingApprovalToolIds?: string[],
            toolResults?: { type: 'echarts' | 'mermaid' | 'math' | 'image' | 'text'; content: string; name?: string }[],
            isError?: boolean,
            errorMessage?: string,
            isLongWait?: boolean
        ) => {
            const key = `${sessionId}:${messageId}`;
            const currentPending = pendingUpdates.get(key) || { content };

            // Merge updates
            pendingUpdates.set(key, {
                ...currentPending,
                content: content, // Always overwrite content with latest
                ...(tokens !== undefined && { tokens }),
                ...(reasoning !== undefined && { reasoning }),
                ...(citations !== undefined && { citations }),
                ...(ragReferences !== undefined && { ragReferences }),
                ...(ragReferencesLoading !== undefined && { ragReferencesLoading }),
                ...(ragMetadata !== undefined && { ragMetadata }),
                ...(thought_signature !== undefined && { thought_signature }),
                ...(taskState !== undefined && { taskState }),
                ...(tool_calls !== undefined && { tool_calls }),
                ...(executionSteps !== undefined && { executionSteps }),
                ...(pendingApprovalToolIds !== undefined && { pendingApprovalToolIds }),
                ...(toolResults !== undefined && { toolResults }),
                ...(isError !== undefined && { isError }),
                ...(errorMessage !== undefined && { errorMessage }),
                ...(isLongWait !== undefined && { isLongWait })
            });

            // If no timer active, schedule flush (Leading Edge + Trailing Edge logic)
            // Implementation: We always delay 200ms. This is "Throttling" (capped at 5fps).
            if (!throttleTimers.has(key)) {
                const timer = setTimeout(() => {
                    flushUpdate(sessionId, messageId);
                }, 200); // 5fps limit for Zustand
                throttleTimers.set(key, timer);
            }
        },

        deleteMessage: async (sessionId: SessionId, messageId: string) => {
            const state = get();
            // If the session being edited is currently generating
            if (state.currentGeneratingSessionId === sessionId) {
                const session = state.sessions.find((s) => s.id === sessionId);
                if (session) {
                    // If we are deleting the last message (which is usually the AI message under generation)
                    const lastMsg = session.messages[session.messages.length - 1];
                    if (lastMsg && lastMsg.id === messageId) {
                        console.log('[MessageManager] Deleting active generating message, aborting...');
                        state.abortGeneration(sessionId);
                    }
                }
            }

            // 🔑 Phase 4b: 从 SQLite 删除
            try {
                await SessionRepository.deleteMessage(sessionId, messageId);
            } catch (e) {
                console.warn('[MessageManager] DB deleteMessage failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? { ...s, messages: s.messages.filter((m) => m.id !== messageId) }
                        : s
                ),
            }));
        },

        deleteMessagesAfter: async (sessionId: SessionId, timestamp: number) => {
            const state = get();
            // 如果会话正在生成，中止它
            if (state.currentGeneratingSessionId === sessionId) {
                console.log('[MessageManager] Truncating while generating, aborting...');
                state.abortGeneration(sessionId);
            }

            // 🔑 Phase 4b: 从 SQLite 删除
            try {
                await SessionRepository.deleteMessagesAfter(sessionId, timestamp);
            } catch (e) {
                console.warn('[MessageManager] DB deleteMessagesAfter failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? { ...s, messages: s.messages.filter((m) => m.createdAt < timestamp) }
                        : s
                ),
            }));
        },



        vectorizeMessage: async (sessionId: string, messageId: string) => {
            const session = get().sessions.find((s) => s.id === sessionId);
            if (!session) return;

            const message = session.messages.find((m) => m.id === messageId);
            if (!message || message.role !== 'user') return;

            try {
                const { MemoryManager } = await import('../../lib/rag/memory-manager');
                await MemoryManager.addTurnToMemory(
                    sessionId,
                    message.content,
                    '', // No assistant response for individual message vectorization
                    message.id,
                    ''
                );
            } catch (error) {
                console.error('[MessageManager] Vectorization failed:', error);
            }
        },

        updateMessageProgress: (sessionId: string, messageId: string, progress: RagProgress) => {
            // 进度更新不写 DB（高频且临时）
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? {
                            ...s,
                            messages: s.messages.map((m) =>
                                m.id === messageId ? { ...m, ragProgress: progress } : m
                            ),
                        }
                        : s
                ),
            }));
        },

        updateMessageLayout: (sessionId: SessionId, messageId: string, height: number) => {
            const session = get().sessions.find((s) => s.id === sessionId);
            if (!session) return;
            const message = session.messages.find((m) => m.id === messageId);

            // 只有当高度未设置，或高度差异超过 10px 时才更新，避免微小抖动导致的频繁写入
            if (message && (!message.layoutHeight || Math.abs(message.layoutHeight - height) > 10)) {
                // 布局高度更新不写 DB（高频且可重计算）
                set((state) => ({
                    sessions: state.sessions.map((s) =>
                        s.id === sessionId
                            ? {
                                ...s,
                                messages: s.messages.map((m) =>
                                    m.id === messageId ? { ...m, layoutHeight: height } : m
                                ),
                            }
                            : s
                    ),
                }));
            }
        },

        // Phase 4a: 从 chat-store.ts 迁移
        setVectorizationStatus: async (sessionId: string, messageIds: string[], status: 'processing' | 'success' | 'error') => {
            // 🔑 Phase 4b: 写入 SQLite
            for (const msgId of messageIds) {
                try {
                    await SessionRepository.updateMessage(sessionId, msgId, {
                        vectorizationStatus: status,
                        isArchived: status === 'success' ? true : undefined,
                    });
                } catch (e) {
                    console.warn('[MessageManager] DB setVectorizationStatus failed:', e);
                }
            }

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
                                        vectorizationStatus: status as Message['vectorizationStatus'],
                                        isArchived: status === 'success' ? true : m.isArchived
                                    } : m
                                ),
                            };
                        }
                        return s;
                    }),
                };
            });
        },

        flushMessageUpdates: (sessionId: string, messageId: string) => {
            flushUpdate(sessionId, messageId);
        }
    };
};
