export type ChatMessageContent =
    | string
    | Array<{ type: 'text'; text: string } | { type: 'image_url'; image_url: { url: string } }>;

export interface ChatMessage {
    role: 'system' | 'user' | 'assistant';
    content: ChatMessageContent;
}

export interface StreamChunk {
    content: string;
    reasoning?: string;
    citations?: { title: string; url: string; source?: string }[];
    done: boolean;
}

export interface ChatMessageOptions {
    webSearch?: boolean;
    reasoning?: boolean;
    inferenceParams?: {
        temperature?: number;
        topP?: number;
        maxTokens?: number;
        frequencyPenalty?: number;
        presencePenalty?: number;
    };
}

export interface LlmClient {
    streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[]; usage?: { input: number; output: number; total: number } }) => void,
        onError: (err: Error) => void,
        options?: ChatMessageOptions
    ): Promise<void>;

    /**
     * 非流式对话，用于工具调用或后台任务
     * @param messages 消息历史
     * @param options 选项
     * @returns 完整回复内容
     */
    chatCompletion(messages: ChatMessage[], options?: any): Promise<string>;
    testConnection(): Promise<{ success: boolean; latency: number; error?: string }>;
    testRerankConnection?(): Promise<{ success: boolean; latency: number; error?: string }>;
    abort?(): void;
}
