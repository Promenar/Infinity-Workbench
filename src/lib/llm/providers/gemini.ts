import { LlmClient, ChatMessage } from '../types';

export class GeminiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;

    constructor(apiKey: string, model: string, temperature: number, baseUrl: string = 'https://generativelanguage.googleapis.com') {
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
        // TODO: 实现 Gemini 原生 API 的 streamChat
        onError(new Error('Gemini native streamChat not yet implemented'));
    }

    async testConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const start = Date.now();
        try {
            // Gemini (Direct API) 通常通过 URL 参数或 x-goog-api-key 头认证
            // 路径: /v1beta/models/{model}:generateContent

            // 兼容性处理：如果 baseUrl 结尾有 /v1，则去掉，因为我们下面会自己补
            const cleanBase = this.baseUrl.replace(/\/v1$/, '').replace(/\/$/, '');
            const endpoint = `${cleanBase}/v1beta/models/${this.model}:generateContent?key=${this.apiKey}`;

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    contents: [{ parts: [{ text: 'ping' }] }],
                    generationConfig: {
                        maxOutputTokens: 1,
                        temperature: this.temperature
                    }
                }),
            });

            const latency = Date.now() - start;

            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                const msg = errorData.error?.message || `HTTP ${response.status}`;
                return { success: false, latency, error: msg };
            }

            return { success: true, latency };
        } catch (e) {
            const latency = Date.now() - start;
            return { success: false, latency, error: (e as Error).message };
        }
    }
}
