/**
 * 消息管理模块
 * 负责消息的创建、更新、删除和向量化
 */

import type { ManagerContext, MessageManager } from './types';
import type { Message, SessionId, TokenUsage, RagReference, RagProgress, RagMetadata, TaskState } from '../../types/chat';

export const createMessageManager = (context: ManagerContext): MessageManager => {
    const { get, set } = context;

    // 🔑 Throttling & Buffer State
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
    }>();

    const throttleTimers = new Map<string, NodeJS.Timeout>();

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
            });

            // If no timer active, schedule flush (Leading Edge + Trailing Edge logic)
            // Implementation: We always delay 100ms. This is "Throttling" (capped at 10fps).
            // For smoother typing effect, we might want "Leading Edge" (immediate first char),
            // but for avoiding freeze, strict throttling is safer.
            // Let's use strict throttling to solve the "DeepSeek Freeze".
            if (!throttleTimers.has(key)) {
                // If it's the very first token (buffer was empty), maybe flush immediately?
                // No, consistency is key. Just invoke in 100ms.
                const timer = setTimeout(() => {
                    flushUpdate(sessionId, messageId);
                }, 200); // 5fps limit
                throttleTimers.set(key, timer);
            }
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
