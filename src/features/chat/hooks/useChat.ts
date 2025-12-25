import { useState, useCallback, useMemo } from 'react';
import { useChatStore } from '../../../store/chat-store';
import { Message } from '../../../types/chat';

export function useChat(sessionId: string) {
    const { getSession, addMessage, updateSession } = useChatStore();
    const session = getSession(sessionId);
    const [loading, setLoading] = useState(false);

    const messages = session?.messages || [];

    const sendMessage = useCallback(async (content: string) => {
        if (!content.trim() || !sessionId) return;

        const userMsg: Message = {
            id: `msg_${Date.now()}`,
            role: 'user',
            content,
            timestamp: Date.now(),
        };

        // 1. Add user message to store
        addMessage(sessionId, userMsg);
        setLoading(true);

        // 2. Mock API Response (In future, this will call real AI with Agent's System Prompt)
        setTimeout(() => {
            const responseMsg: Message = {
                id: `msg_ai_${Date.now()}`,
                role: 'assistant',
                content: `I am processing your request. You said: "${content}"`,
                timestamp: Date.now(),
            };

            // Add assistant response to store
            addMessage(sessionId, responseMsg);

            // If it's the first message, update session title
            if (messages.length === 0) {
                updateSession(sessionId, { title: content.substring(0, 30) + (content.length > 30 ? '...' : '') });
            }

            setLoading(false);
        }, 1200);
    }, [sessionId, messages.length]);

    return {
        messages,
        loading,
        sendMessage,
    };
}
