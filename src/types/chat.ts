export type AgentId = string;
export type SessionId = string;

export interface InferenceParams {
    temperature?: number;      // 0.0 - 2.0
    topP?: number;            // 0.0 - 1.0
    maxTokens?: number;       // 1 - Context Limit
    frequencyPenalty?: number; // -2.0 - 2.0
    presencePenalty?: number;  // -2.0 - 2.0
}

export interface Agent {
    id: string;
    name: string;
    description: string;
    avatar: string; // lucide icon name or image url
    color: string;

    // AI Configuration
    systemPrompt: string;
    defaultModel: string;

    // Advanced Parameters
    params?: InferenceParams; // Default params for this agent

    isPreset?: boolean;
    isPinned?: boolean;
    created: number;
}

export interface TokenUsage {
    input: number;
    output: number;
    total: number;
}

/**
 * 生成图片数据（支持缩略图）
 */
export interface GeneratedImageData {
    thumbnail: string;  // 缩略图 URI (file://)
    original: string;   // 原图 URI (file://)
    mime: string;       // MIME 类型
}

export interface RagReference {
    id: string;           // 引用 ID
    content: string;      // 片段内容
    source: string;       // 来源（文档名或会话标题）
    type: 'doc' | 'memory';  // 类型
    docId?: string;       // 文档 ID（用于跳转）
    similarity?: number;  // 相似度分数
}

export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    timestamp: number;
    modelId?: string; // The model used to generate this message
    reasoning?: string; // Chain of Thought reasoning content
    citations?: { title: string; url: string; source?: string }[]; // Grounding/Web Search citations
    ragReferences?: RagReference[]; // RAG knowledge base references
    ragReferencesLoading?: boolean; // New flag for RAG search state
    tokens?: TokenUsage;
    images?: GeneratedImageData[]; // 图片数据（新格式，支持缩略图）
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
    inferenceParams?: InferenceParams;
    options?: {
        webSearch?: boolean;
        reasoning?: boolean;
    };
    ragOptions?: {
        enableMemory?: boolean;  // 启用长期记忆
        enableDocs?: boolean;    // 启用知识库检索
        activeDocIds?: string[]; // 指定文档ID（undefined=全部）
        activeFolderIds?: string[]; // 指定文件夹ID
        isGlobal?: boolean;      // 是否全局搜索（忽略 activeDocIds）
    };
    scrollOffset?: number; // 记录滚动位置
    draft?: string; // 未发送的草稿内容
}
