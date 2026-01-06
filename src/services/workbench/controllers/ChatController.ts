import { useChatStore } from '../../../store/chat-store';
import { useAgentStore } from '../../../store/agent-store';
import { RouterContext } from '../WorkbenchRouter';

export const ChatController = {
    async getSessions(payload: any, context: RouterContext) {
        const { sessions } = useChatStore.getState();
        // Return summary list (lightweight)
        return sessions.map((s: any) => ({
            id: s.id,
            title: s.title,
            agentId: s.agentId,
            updatedAt: s.updatedAt,
            lastMessage: s.lastMessage,
            modelId: s.modelId
        })).sort((a: any, b: any) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },

    async getSessionHistory(payload: any, context: RouterContext) {
        const { id } = payload;
        if (!id) throw new Error('Session ID required');

        const session = useChatStore.getState().getSession(id);
        if (!session) throw new Error('Session not found');

        return session;
    },

    async createSession(payload: any, context: RouterContext) {
        const { agentId } = payload;
        if (!agentId) throw new Error('Agent ID required');

        const { addSession } = useChatStore.getState();
        const { getAgent } = useAgentStore.getState();

        const agent = getAgent(agentId);
        if (!agent) throw new Error('Agent not found');

        const newSession = {
            id: crypto.randomUUID(),
            title: 'New Chat',
            agentId,
            messages: [],
            createdAt: Date.now(),
            updatedAt: Date.now(),
            modelId: agent.defaultModel
        };

        addSession(newSession as any);
        return newSession;
    },

    async deleteSession(payload: any, context: RouterContext) {
        const { id } = payload;
        if (!id) throw new Error('Session ID required');

        const { deleteSession } = useChatStore.getState();
        deleteSession(id);

        return { success: true, id };
    }
};
