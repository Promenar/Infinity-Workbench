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
                        executionMode: session.executionMode || 'auto',
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
    };
};
