/**
 * 会话管理模块 (🔑 Phase 4b: SQLite 双写模式)
 * 负责会话的创建、更新、删除和查询
 * 所有写操作同时更新 SQLite 和 Zustand 状态
 */

import type { ManagerContext, SessionManager } from './types';
import type { Session, SessionId, InferenceParams } from '../../types/chat';
import { SessionRepository } from '../../lib/db/session-repository';
import { db } from '../../lib/db';

import { useMcpStore } from '../../store/mcp-store';

export const createSessionManager = (context: ManagerContext): SessionManager => {
    const { get, set } = context;

    return {
        addSession: async (session: Session) => {
            // 🔑 Phase 4b: 双写模式
            // 🔑 MCP Initialization: Inherit default included servers
            const defaultMcpServers = useMcpStore.getState().servers
                .filter(s => s.enabled && s.defaultIncluded)
                .map(s => s.id);

            const enrichedSession: Session = {
                ...session,
                executionMode: session.executionMode || 'semi',
                loopStatus: session.loopStatus || 'completed',
                activeMcpServerIds: session.activeMcpServerIds || defaultMcpServers,
                activeSkillIds: session.activeSkillIds || [],
            };

            // 1. 写入 SQLite
            try {
                await SessionRepository.create(enrichedSession);
            } catch (e) {
                console.warn('[SessionManager] DB write failed, continuing with memory only:', e);
            }

            // 2. 更新 Zustand 缓存
            set((state) => ({
                sessions: [enrichedSession, ...state.sessions],
            }));
        },

        updateSession: async (id: SessionId, updates: Partial<Session>) => {
            // 1. 写入 SQLite
            try {
                await SessionRepository.update(id, updates);
            } catch (e) {
                console.warn('[SessionManager] DB update failed:', e);
            }

            // 2. 更新 Zustand 缓存
            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, ...updates } : s)),
            }));
        },

        deleteSession: async (id: SessionId) => {
            // 1. 清理会话关联的知识图谱数据（kg_edges/kg_nodes 无 session_id 外键约束，需手动清理）
            try {
                await db.execute('DELETE FROM kg_edges WHERE session_id = ?', [id]);
                await db.execute(`
                    DELETE FROM kg_nodes 
                    WHERE session_id = ? 
                    AND id NOT IN (SELECT source_id FROM kg_edges)
                    AND id NOT IN (SELECT target_id FROM kg_edges)
                `, [id]);
            } catch (e) {
                console.warn('[SessionManager] KG cleanup failed:', e);
            }

            // 2. 从 SQLite 删除（CASCADE 自动删除 messages 和 vectors）
            try {
                await SessionRepository.delete(id);
            } catch (e) {
                console.warn('[SessionManager] DB delete failed:', e);
            }

            // 3. 更新 Zustand 缓存
            set((state) => ({
                sessions: state.sessions.filter((s) => s.id !== id),
            }));
        },

        getSession: (id: SessionId) => {
            return get().sessions.find((s) => s.id === id);
        },

        updateSessionDraft: async (sessionId: SessionId, draft: string | undefined) => {
            try {
                await SessionRepository.update(sessionId, { draft });
            } catch (e) {
                console.warn('[SessionManager] DB update draft failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, draft } : s
                ),
            }));
        },

        toggleSessionPin: async (sessionId: SessionId) => {
            const session = get().sessions.find((s) => s.id === sessionId);
            const newPinned = !session?.isPinned;

            try {
                await SessionRepository.update(sessionId, { isPinned: newPinned });
            } catch (e) {
                console.warn('[SessionManager] DB toggle pin failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === sessionId ? { ...s, isPinned: newPinned } : s
                ),
            }));
        },

        updateSessionInferenceParams: async (id: SessionId, params: InferenceParams) => {
            try {
                await SessionRepository.update(id, { inferenceParams: params });
            } catch (e) {
                console.warn('[SessionManager] DB update params failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === id ? { ...s, inferenceParams: params } : s
                ),
            }));
        },

        // Phase 4a: 从 chat-store.ts 迁移的辅助方法
        updateSessionTitle: async (id: SessionId, title: string) => {
            try {
                await SessionRepository.update(id, { title });
            } catch (e) {
                console.warn('[SessionManager] DB update title failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, title } : s)),
            }));
        },

        updateSessionPrompt: async (id: SessionId, prompt: string | undefined) => {
            try {
                await SessionRepository.update(id, { customPrompt: prompt });
            } catch (e) {
                console.warn('[SessionManager] DB update prompt failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, customPrompt: prompt } : s)),
            }));
        },

        updateSessionModel: async (id: SessionId, modelId: string | undefined) => {
            try {
                await SessionRepository.update(id, { modelId });
            } catch (e) {
                console.warn('[SessionManager] DB update model failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) => (s.id === id ? { ...s, modelId } : s)),
            }));
        },

        updateSessionOptions: async (id: SessionId, options: any) => {
            const session = get().sessions.find((s) => s.id === id);
            const newOptions = { ...session?.options, ...options };
            const newRagOptions = { ...session?.ragOptions, ...options?.ragOptions };

            try {
                await SessionRepository.update(id, {
                    options: newOptions,
                    ragOptions: newRagOptions
                });
            } catch (e) {
                console.warn('[SessionManager] DB update options failed:', e);
            }

            set((state) => ({
                sessions: state.sessions.map((s) =>
                    s.id === id
                        ? {
                            ...s,
                            options: newOptions,
                            ragOptions: newRagOptions,
                        }
                        : s
                ),
            }));
        },

        updateSessionScrollOffset: async (id: SessionId, offset: number) => {
            // 滚动偏移量高频更新，使用防抖
            // 暂时只更新 Zustand，不写 DB（可后续优化）
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

        dismissActiveTask: async (sessionId: SessionId) => {
            try {
                await SessionRepository.update(sessionId, { activeTask: undefined });
            } catch (e) {
                console.warn('[SessionManager] DB dismiss task failed:', e);
            }

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

        toggleMcpServer: async (sessionId: SessionId, serverId: string) => {
            const session = get().sessions.find(s => s.id === sessionId);
            if (!session) return;

            const current = session.activeMcpServerIds || [];
            const next = current.includes(serverId)
                ? current.filter(id => id !== serverId)
                : [...current, serverId];

            await SessionRepository.update(sessionId, { activeMcpServerIds: next });
            set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? { ...s, activeMcpServerIds: next } : s)
            }));
        },

        toggleSkill: async (sessionId: SessionId, skillId: string) => {
            const session = get().sessions.find(s => s.id === sessionId);
            if (!session) return;

            const current = session.activeSkillIds || [];
            const next = current.includes(skillId)
                ? current.filter(id => id !== skillId)
                : [...current, skillId];

            await SessionRepository.update(sessionId, { activeSkillIds: next });
            set(state => ({
                sessions: state.sessions.map(s => s.id === sessionId ? { ...s, activeSkillIds: next } : s)
            }));
        }
    };
};
