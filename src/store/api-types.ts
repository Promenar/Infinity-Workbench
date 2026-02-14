// 支持的服务商类型
export type ApiProviderType =
    | 'openai'
    | 'anthropic'
    | 'google' // VertexAI
    | 'gemini' // Direct Gemini API
    | 'deepseek'
    | 'moonshot'
    | 'zhipu'
    | 'siliconflow'
    | 'github'
    | 'cloudflare'
    | 'github-copilot'
    | 'openai-compatible'
    | 'local';

// 模型能力标识
export interface ModelCapabilities {
    vision?: boolean;
    internet?: boolean;
    reasoning?: boolean; // 思考模型（如 R1, o1）
    tools?: boolean;
}

// 模型配置接口
export interface ModelConfig {
    uuid: string; // 内部稳定标识符，用于 React 渲染 key
    id: string; // API 调用参数 (如 "gpt-4o")
    name: string; // 显示名称
    type?: 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank';
    contextLength?: number;
    capabilities: ModelCapabilities;
    enabled: boolean;
    isAutoFetched?: boolean;
    icon?: string;
}

// 服务商配置接口
export interface ProviderConfig {
    id: string;
    name: string;
    type: ApiProviderType;
    apiKey: string;
    baseUrl?: string;
    enabled: boolean;
    models: ModelConfig[]; // 模型列表
    // VertexAI 特定字段
    vertexProject?: string;
    vertexLocation?: string;
    vertexKeyJson?: string;
}

// 模型统计接口
export interface TokenStats {
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    lastUsed?: number;
}
