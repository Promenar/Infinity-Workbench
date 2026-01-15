/**
 * 消息管理模块
 * 负责消息的创建、更新、删除和向量化
 */

import type { ManagerContext, MessageManager } from './types';
import type { Message, SessionId, TokenUsage, RagReference, RagProgress, RagMetadata, TaskState } from '../../types/chat';

export const createMessageManager = (context: ManagerContext): MessageManager => {
    const { get, set } = context;

    return {
        addMessage: (sessionId: string, message: Message) => {
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
            taskState?: TaskState
        ) => {
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
                                            ...(reasoning !== undefined && { reasoning }),
                                            ...(citations !== undefined && { citations }),
                                            ...(ragReferences !== undefined && { ragReferences }),
                                            ...(ragReferencesLoading !== undefined && { ragReferencesLoading }),
                                            ...(ragMetadata !== undefined && { ragMetadata }),
                                            ...(thought_signature !== undefined && { thought_signature }),
                                            ...(taskState && { planningTask: taskState }),
                                        }
                                        : m
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
            });
        },

        deleteMessage: (sessionId: SessionId, messageId: string) => {
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

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? { ...s, messages: s.messages.filter((m) => m.id !== messageId) }
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

            // 只有当高度未设置，或高度差异超过 2px 时才更新，避免微小抖动导致的频繁写入
            if (message && (!message.layoutHeight || Math.abs(message.layoutHeight - height) > 2)) {
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
        setVectorizationStatus: (sessionId: string, messageIds: string[], status: 'processing' | 'success' | 'error') => {
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
    };
};
