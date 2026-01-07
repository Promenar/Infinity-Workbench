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
    isArchived?: boolean;
    vectorizationStatus?: 'processing' | 'success' | 'error';
    citations?: { title: string; url: string; source?: string }[];
    reasoning?: string;
    ragReferences?: RagReference[];
}

export interface RagReference {
    id: string;
    content: string;
    score: number;
    metadata?: any;
    source?: string;
}

export type Message = ChatMessage;
