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
            lastMessage: typeof s.lastMessage === 'string'
                ? (s.lastMessage.length > 200 ? s.lastMessage.substring(0, 200) + '...' : s.lastMessage)
                : (s.lastMessage?.content ? (s.lastMessage.content.length > 200 ? s.lastMessage.content.substring(0, 200) + '...' : s.lastMessage.content) : ''),
            modelId: s.modelId
        })).sort((a: any, b: any) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },

    async getSessionHistory(payload: any, context: RouterContext) {
        const { id } = payload;
        if (!id) throw new Error('Session ID required');

        const session = useChatStore.getState().getSession(id);
        if (!session) throw new Error('Session not found');

        const str = JSON.stringify(session);
        if (str.length > 10000) {
            console.log(`[ChatController] Sending large session history: ${str.length} chars (ID: ${id})`);
        }

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
            modelId: agent.defaultModel,
            executionMode: 'auto' as const,
            loopStatus: 'completed' as const
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
    },

    async sendMessage(payload: any, context: RouterContext) {
        const { sessionId, content, options } = payload;
        if (!sessionId || !content) throw new Error('Session ID and Content required');

        // Trigger generation in background (fire and forget from API perspective)
        // StoreSyncService will handle the updates
        const { generateMessage } = useChatStore.getState();
        // Check if session exists first? generateMessage does valid checks.

        // We don't await the full generation here, just the initiation.
        // Actually generateMessage IS async (Promise<void>).
        // If we await it, this HTTP request hangs until generation is done.
        // We should NOT await it for streaming UX, 
        // BUT we should verify it started successfully.

        generateMessage(sessionId, content, options).catch(err => {
            console.error('[ChatController] Generation failed:', err);
        });

        return { success: true, status: 'generating' };
    },

    async abortGeneration(payload: any, context: RouterContext) {
        const { sessionId } = payload;
        if (!sessionId) throw new Error('Session ID required');

        const { abortGeneration } = useChatStore.getState();
        abortGeneration(sessionId);

        return { success: true };
    },

    async deleteMessage(payload: any, context: RouterContext) {
        const { sessionId, messageId } = payload;
        if (!sessionId || !messageId) throw new Error('Session ID and Message ID required');

        const { deleteMessage } = useChatStore.getState();
        deleteMessage(sessionId, messageId);

        return { success: true, messageId };
    },

    async regenerateMessage(payload: any, context: RouterContext) {
        const { sessionId, messageId } = payload;
        if (!sessionId || !messageId) throw new Error('Session ID and Message ID required');

        // Fire and forget (it streams updates via other channels)
        const { regenerateMessage } = useChatStore.getState();
        regenerateMessage(sessionId, messageId).catch(err => {
            console.error('[ChatController] Regenerate failed:', err);
        });

        return { success: true };
    }
};
