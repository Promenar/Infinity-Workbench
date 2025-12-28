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

export interface TokenUsage {
    input: number;
    output: number;
    total: number;
}

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    timestamp: number;
    modelId?: string; // The model used to generate this message
    reasoning?: string; // Chain of Thought reasoning content
    citations?: { title: string; url: string; source?: string }[]; // Grounding/Web Search citations
    tokens?: TokenUsage;
    images?: string[]; // Local object URL or file path (file://)
}

export interface Session {
    id: SessionId;
    agentId: AgentId;
    title: string;
    lastMessage: string;
    time: string;
    unread: number;
    messages: Message[];
    modelId?: string; // Override agent's default model for this session
    customPrompt?: string; // Additional prompt specific to this session (appended to agent's systemPrompt)
    isPinned?: boolean;
    stats?: {
        totalTokens: number;
    };
    options?: {
        webSearch?: boolean;
        reasoning?: boolean;
    };
    ragOptions?: {
        enableMemory?: boolean;  // 启用长期记忆
        enableDocs?: boolean;    // 启用知识库检索
        activeDocIds?: string[]; // 指定文档ID（undefined=全部）
        activeFolderIds?: string[]; // 指定文件夹ID
    };
    scrollOffset?: number; // 记录滚动位置
}
