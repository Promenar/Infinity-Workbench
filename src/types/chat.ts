export type AgentId = string;
export type SessionId = string;

export interface Agent {
    id: AgentId;
    name: string;
    description: string;
    avatar: string; // Icon name or image URI
    color: string;

    // AI Configuration
    systemPrompt: string;
    defaultModel: string;

    // Advanced Parameters
    params: {
        temperature: number;
        maxTokens?: number;
        topP?: number;
    };

    isPreset?: boolean;
    created: number;
}

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    timestamp: number;
}

export interface Session {
    id: SessionId;
    agentId: AgentId;
    title: string;
    lastMessage: string;
    time: string;
    unread: number;
    messages: Message[];
}
