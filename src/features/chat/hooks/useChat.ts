import { useState, useCallback } from 'react';
import { db } from '../../../lib/db'; // Import connection for future use
// import { useToast } from '../../../components/ui/Toast';

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    created_at: number;
}

export function useChat(sessionId: string = 'default') {
    // const { showToast } = useToast();
    const [messages, setMessages] = useState<Message[]>([
        {
            id: '1',
            role: 'assistant',
            content: '# Hello! \nI am **NeuralFlow**, your AI assistant. \n\nHow can I help you today?',
            created_at: Date.now(),
        },
    ]);
    const [loading, setLoading] = useState(false);
    const [mode, setMode] = useState<'chat' | 'writer'>('chat');

    const sendMessage = useCallback(async (content: string) => {
        if (!content.trim()) return;

        const userMsg: Message = {
            id: Date.now().toString(), // Temp ID
            role: 'user',
            content,
            created_at: Date.now(),
        };

        setMessages((prev) => [...prev, userMsg]);
        setLoading(true);

        // Mock API delay
        setTimeout(() => {
            const responseMsg: Message = {
                id: (Date.now() + 1).toString(),
                role: 'assistant',
                content: `[${mode.toUpperCase()}] I received your message: "${content}". \n\n*This is a mock response.*`,
                created_at: Date.now(),
            };
            setMessages((prev) => [...prev, responseMsg]);
            setLoading(false);
        }, 1000);
    }, [mode]);

    return {
        messages,
        loading,
        sendMessage,
        mode,
        setMode,
    };
}
