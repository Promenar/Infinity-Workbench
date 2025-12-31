import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { ErrorNormalizer } from '../error-normalizer';

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
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[]; usage?: { input: number; output: number; total: number } }) => void,
        onError: (err: Error) => void,
        options?: ChatMessageOptions
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
                                const usageRaw = json.usage;

                                let usage: { input: number; output: number; total: number } | undefined;
                                if (usageRaw) {
                                    usage = {
                                        input: usageRaw.prompt_tokens,
                                        output: usageRaw.completion_tokens,
                                        total: usageRaw.total_tokens
                                    };
                                }

                                if (content || reasoning || usage) {
                                    onChunk({ content, reasoning, usage });
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
                            // 使用错误标准化器处理API错误
                            const rawError = {
                                status: xhr.status,
                                statusText: xhr.statusText,
                                message: `API Error: ${xhr.status} ${xhr.statusText}\n${xhr.responseText}`,
                                response: xhr.responseText
                            };

                            const normalized = ErrorNormalizer.normalize(rawError, 'openai');
                            const err = new Error(normalized.message);
                            (err as any).category = normalized.category;
                            (err as any).retryable = normalized.retryable;
                            (err as any).retryAfter = normalized.retryAfter;
                            (err as any).technicalMessage = normalized.technicalMessage;

                            onError(err);
                            reject(err);
                        }
                    }
                };

                xhr.onerror = () => {
                    const rawError = new Error('Network request failed');
                    const normalized = ErrorNormalizer.normalize(rawError, 'openai');
                    const err = new Error(normalized.message);
                    (err as any).category = normalized.category;
                    (err as any).retryable = normalized.retryable;

                    onError(err);
                    reject(err);
                };

                xhr.send(JSON.stringify({
                    model: this.model,
                    messages,
                    temperature: options?.inferenceParams?.temperature ?? this.temperature,
                    top_p: options?.inferenceParams?.topP,
                    max_tokens: options?.inferenceParams?.maxTokens,
                    frequency_penalty: options?.inferenceParams?.frequencyPenalty,
                    presence_penalty: options?.inferenceParams?.presencePenalty,
                    stream: true,
                    stream_options: { include_usage: true }
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
                // Rule 8.4: Capture HTML error pages
                const contentType = response.headers.get('Content-Type') || '';
                const errorText = await response.text();

                if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
                    return { success: false, latency, error: `HTTP ${response.status}: Received non-JSON response (possibly HTML error page).` };
                }

                return { success: false, latency, error: `HTTP ${response.status}: ${errorText.substring(0, 100)}` };
            }

            return { success: true, latency };
        } catch (e) {
            const latency = Date.now() - start;
            return { success: false, latency, error: (e as Error).message };
        }
    }

    async testRerankConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const start = Date.now();
        try {
            const endpoint = `${this.baseUrl}/v1/rerank`;

            const body = {
                model: this.model,
                query: "测试查询",
                documents: ["文档1", "文档2"],
                top_n: 2
            };

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
                const contentType = response.headers.get('Content-Type') || '';
                const errorText = await response.text();

                if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
                    return { success: false, latency, error: `HTTP ${response.status}: Received non-JSON response (possibly HTML error page).` };
                }

                return { success: false, latency, error: `HTTP ${response.status}: ${errorText.substring(0, 100)}` };
            }

            return { success: true, latency };
        } catch (e) {
            const latency = Date.now() - start;
            return { success: false, latency, error: (e as Error).message };
        }
    }
}
