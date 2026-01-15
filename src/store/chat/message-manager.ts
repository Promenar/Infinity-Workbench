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
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? { ...s, messages: [...s.messages, message] }
                        : s
                ),
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
            streaming?: boolean,
            ragProgress?: RagProgress,
            ragMetadata?: RagMetadata,
            taskState?: TaskState
        ) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId
                        ? {
                            ...s,
                            messages: s.messages.map((m) =>
                                m.id === messageId
                                    ? {
                                        ...m,
                                        content,
                                        ...(tokens && { tokens }),
                                        ...(reasoning !== undefined && { reasoning }),
                                        ...(citations && { citations }),
                                        ...(ragReferences && { ragReferences }),
                                        ...(streaming !== undefined && { status: streaming ? 'streaming' : 'sent' }),
                                        ...(ragProgress && { ragProgress }),
                                        ...(ragMetadata && { ragMetadata }),
                                        ...(taskState && { planningTask: taskState }),
                                    }
                                    : m
                            ),
                        }
                        : s
                ),
            }));
        },

        deleteMessage: (sessionId: SessionId, messageId: string) => {
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
        },
    };
};
