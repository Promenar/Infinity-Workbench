export interface ChatMessage {
    role: 'system' | 'user' | 'assistant';
    content: string;
}

export interface StreamChunk {
    content: string;
    done: boolean;
}

export interface LlmClient {
    streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: string) => void,
        onError: (err: Error) => void
    ): Promise<void>;
}
