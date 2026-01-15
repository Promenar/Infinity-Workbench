/**
 * 会话管理模块
 * 负责会话的创建、更新、删除和查询
 */

import type { ManagerContext, SessionManager } from './types';
import type { Session, SessionId, InferenceParams } from '../../types/chat';

export const createSessionManager = (context: ManagerContext): SessionManager => {
    const { get, set } = context;

    return {
        addSession: (session: Session) => {
            set((state) => ({
                sessions: [
                    {
                        ...session,
                        executionMode: session.executionMode || 'semi',
                        loopStatus: session.loopStatus || 'completed',
                    },
                    ...state.sessions,
                ],
            }));
        },

        updateSession: (id: SessionId, updates: Partial<Session>) => {
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, ...updates } : s)),
            }));
        },

        deleteSession: (id: SessionId) => {
            set((state) => ({
                sessions: state.sessions.filter((s) => s.id !== id),
            }));
        },

        getSession: (id: SessionId) => {
            return get().sessions.find((s) => s.id === id);
        },

        updateSessionDraft: (sessionId: SessionId, draft: string | undefined) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, draft } : s
                ),
            }));
        },

        toggleSessionPin: (sessionId: SessionId) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, isPinned: !s.isPinned } : s
                ),
            }));
        },

        updateSessionInferenceParams: (id: SessionId, params: InferenceParams) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === id ? { ...s, inferenceParams: params } : s
                ),
            }));
        },

        // Phase 4a: 从 chat-store.ts 迁移的辅助方法
        updateSessionTitle: (id: SessionId, title: string) => {
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, title } : s)),
            }));
        },

        updateSessionPrompt: (id: SessionId, prompt: string | undefined) => {
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, customPrompt: prompt } : s)),
            }));
        },

        updateSessionModel: (id: SessionId, modelId: string | undefined) => {
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, modelId } : s)),
            }));
        },

        updateSessionOptions: (id: SessionId, options: any) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === id
                        ? {
                            ...s,
                            options: { ...s.options, ...options },
                            ragOptions: { ...s.ragOptions, ...options?.ragOptions },
                        }
                        : s
                ),
            }));
        },

        updateSessionScrollOffset: (id: SessionId, offset: number) => {
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, scrollOffset: offset } : s)),
            }));
        },

        getSessionsByAgent: (agentId: string) => {
            const sessions = get().sessions.filter((s) => s.agentId === agentId);
            return sessions.sort((a, b) => {
                if (a.isPinned === b.isPinned) return 0;
                return a.isPinned ? -1 : 1;
            });
        },

        dismissActiveTask: (sessionId: SessionId) => {
            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, activeTask: undefined } : s
                ),
            }));
        },

        setKGExtractionStatus: (sessionId: SessionId, isExtracting: boolean) => {
            // 注意：此方法操作的是 activeKGExtractions 状态，需通过 context 访问
            // 暂时返回空实现，待 chat-store 层面处理
        },
    };
};
