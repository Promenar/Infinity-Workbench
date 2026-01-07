export interface StreamMessage {
    sessionId: string;
    messageId: string;
    content: string;
    isDone: boolean;
}

export interface ChatMessage {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    createdAt: number;
    modelId?: string;
    images?: any[];
}
