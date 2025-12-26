import { LlmClient, ChatMessage } from '../types';

export class OpenAiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;

    constructor(apiKey: string, model: string, temperature: number, baseUrl: string = 'https://api.openai.com/v1') {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: string) => void,
        onError: (err: Error) => void
    ): Promise<void> {
        try {
            const response = await fetch(`${this.baseUrl}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${this.apiKey}`,
                },
                body: JSON.stringify({
                    model: this.model,
                    messages,
                    temperature: this.temperature,
                    stream: true,
                }),
            });

            if (!response.ok) {
                throw new Error(`API Error: ${response.statusText}`);
            }

            const reader = response.body?.getReader();
            if (!reader) {
                const text = await response.text();
                this.parseFullResponse(text, onChunk);
                return;
            }

            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, { stream: true });

                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        const trimmedLine = line.trim();
                        if (!trimmedLine || !trimmedLine.startsWith('data: ')) continue;

                        const data = trimmedLine.slice(6);
                        if (data === '[DONE]') break;

                        try {
                            const json = JSON.parse(data);
                            const content = json.choices?.[0]?.delta?.content;
                            if (content) onChunk(content);
                        } catch (e) {
                            // Ignore malformed chunks
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }

        } catch (e) {
            onError(e as Error);
        }
    }

    private parseFullResponse(text: string, onChunk: (chunk: string) => void) {
        const lines = text.split('\n');
        for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed || !trimmed.startsWith('data: ')) continue;
            const data = trimmed.slice(6);
            if (data === '[DONE]') break;
            try {
                const json = JSON.parse(data);
                const content = json.choices?.[0]?.delta?.content;
                if (content) onChunk(content);
            } catch (e) { }
        }
    }

    async testConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const start = Date.now();
        try {
            // 注意：针对不同模型（Chat vs Embedding）可能需要不同的探测路径
            // 简单起见，这里先尝试通用的探测方式或根据模型 ID 特征判断
            const isEmbedding = this.model.toLowerCase().includes('embedding');
            const endpoint = isEmbedding ? `${this.baseUrl}/embeddings` : `${this.baseUrl}/chat/completions`;

            const body = isEmbedding
                ? { model: this.model, input: 'ping' }
                : { model: this.model, messages: [{ role: 'user', content: 'ping' }], max_tokens: 1 };

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${this.apiKey}`,
                },
                body: JSON.stringify(body),
            });

            const latency = Date.now() - start;

            if (!response.ok) {
                const errorText = await response.text();
                return { success: false, latency, error: `HTTP ${response.status}: ${errorText.substring(0, 100)}` };
            }

            return { success: true, latency };
        } catch (e) {
            const latency = Date.now() - start;
            return { success: false, latency, error: (e as Error).message };
        }
    }
}
