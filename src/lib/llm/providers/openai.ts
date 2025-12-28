import { LlmClient, ChatMessage } from '../types';

export class OpenAiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;
    private activeXhr: XMLHttpRequest | null = null;
    private isEmbedding: boolean = false;

    constructor(apiKey: string, model: string, temperature: number, baseUrl: string = 'https://api.openai.com/v1', options?: { isEmbedding?: boolean }) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.isEmbedding = options?.isEmbedding ?? false;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[] }) => void,
        onError: (err: Error) => void,
        options?: { webSearch?: boolean; reasoning?: boolean }
    ): Promise<void> {
        return new Promise((resolve, reject) => {
            try {
                this.activeXhr = new XMLHttpRequest();
                const xhr = this.activeXhr;
                xhr.open('POST', `${this.baseUrl}/chat/completions`);
                xhr.setRequestHeader('Content-Type', 'application/json');
                xhr.setRequestHeader('Authorization', `Bearer ${this.apiKey}`);

                let lastPosition = 0;

                xhr.onreadystatechange = () => {
                    if (xhr.readyState === 3 || xhr.readyState === 4) {
                        const newText = xhr.responseText.substring(lastPosition);
                        lastPosition = xhr.responseText.length;

                        const lines = newText.split('\n');
                        for (const line of lines) {
                            const trimmed = line.trim();
                            if (!trimmed || !trimmed.startsWith('data: ')) continue;
                            const data = trimmed.slice(6);
                            if (data === '[DONE]') {
                                resolve();
                                return;
                            }
                            try {
                                const json = JSON.parse(data);
                                const delta = json.choices?.[0]?.delta;
                                const content = delta?.content || '';
                                const reasoning = delta?.reasoning_content;

                                if (content || reasoning) {
                                    onChunk({ content, reasoning });
                                }
                            } catch (e) {
                                // Partial JSON, ignore or wait
                            }
                        }
                    }
                    if (xhr.readyState === 4) {
                        // status === 0 means aborted, don't treat as error
                        if (xhr.status === 0) {
                            resolve();
                            return;
                        }
                        if (xhr.status >= 200 && xhr.status < 300) {
                            resolve();
                        } else {
                            const err = new Error(`API Error: ${xhr.status} ${xhr.statusText}\n${xhr.responseText}`);
                            onError(err);
                            reject(err);
                        }
                    }
                };

                xhr.onerror = () => {
                    const err = new Error('Network request failed');
                    onError(err);
                    reject(err);
                };

                xhr.send(JSON.stringify({
                    model: this.model,
                    messages,
                    temperature: this.temperature,
                    stream: true,
                }));

            } catch (e) {
                onError(e as Error);
                reject(e);
            } finally {
                // We keep it assigned until readyState 4 finishes
            }
        });
    }

    abort() {
        if (this.activeXhr) {
            this.activeXhr.abort();
            this.activeXhr = null;
        }
    }

    async testConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const start = Date.now();
        try {
            // Use explicit flag if available, otherwise fallback to name check (bge, gte, etc for wider support)
            const isEmbeddingModel = this.isEmbedding ||
                this.model.toLowerCase().includes('embedding') ||
                this.model.toLowerCase().includes('bge') ||
                this.model.toLowerCase().includes('gte') ||
                this.model.toLowerCase().includes('vector');

            const endpoint = isEmbeddingModel ? `${this.baseUrl}/embeddings` : `${this.baseUrl}/chat/completions`;

            const body = isEmbeddingModel
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
