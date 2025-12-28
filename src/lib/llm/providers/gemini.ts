import { LlmClient, ChatMessage } from '../types';

export class GeminiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;
    private activeXhr: XMLHttpRequest | null = null;

    constructor(apiKey: string, model: string, temperature: number, baseUrl: string = 'https://generativelanguage.googleapis.com') {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[] }) => void,
        onError: (err: Error) => void,
        options?: { webSearch?: boolean; reasoning?: boolean }
    ): Promise<void> {
        return new Promise((resolve, reject) => {
            try {
                const cleanBase = this.baseUrl.replace(/\/v1$/, '').replace(/\/$/, '');
                const endpoint = `${cleanBase}/v1beta/models/${this.model}:streamGenerateContent?key=${this.apiKey}`;

                this.activeXhr = new XMLHttpRequest();
                const xhr = this.activeXhr;
                xhr.open('POST', endpoint);
                xhr.setRequestHeader('Content-Type', 'application/json');

                const formatMessage = (m: ChatMessage) => {
                    if (typeof m.content === 'string') {
                        return { matches: true, parts: [{ text: m.content }] };
                    }
                    if (Array.isArray(m.content)) {
                        const parts = m.content.map((c: any) => {
                            if (c.type === 'text') return { text: c.text };
                            if (c.type === 'image_url') {
                                // Extract base64 from data URI
                                const matches = c.image_url.url.match(/^data:(.+);base64,(.+)$/);
                                if (matches) {
                                    return {
                                        inline_data: {
                                            mime_type: matches[1],
                                            data: matches[2]
                                        }
                                    };
                                }
                            }
                            return null;
                        }).filter(Boolean);
                        return { matches: true, parts };
                    }
                    return { matches: false, parts: [] };
                };

                let body: any = {
                    contents: messages.map(m => ({
                        role: m.role === 'assistant' ? 'model' : 'user',
                        parts: formatMessage(m).parts
                    })),
                    generationConfig: {
                        temperature: this.temperature || 0.7,
                        ...(options?.reasoning ? {
                            thinkingConfig: {
                                includeThoughts: true
                            }
                        } : {})
                    }
                };

                if (options?.webSearch) {
                    body.tools = [{ googleSearch: {} }];
                }

                let lastPosition = 0;
                let buffer = '';

                xhr.onreadystatechange = () => {
                    if (xhr.readyState === 3 || xhr.readyState === 4) {
                        const newText = xhr.responseText.substring(lastPosition);
                        lastPosition = xhr.responseText.length;
                        buffer += newText;

                        let startIdx = 0;
                        let bracketCount = 0;
                        let insideString = false;
                        let escape = false;

                        for (let i = 0; i < buffer.length; i++) {
                            const char = buffer[i];
                            if (char === '"' && !escape) insideString = !insideString;
                            if (!insideString) {
                                if (char === '{') {
                                    if (bracketCount === 0) startIdx = i;
                                    bracketCount++;
                                } else if (char === '}') {
                                    bracketCount--;
                                    if (bracketCount === 0) {
                                        const objStr = buffer.substring(startIdx, i + 1);
                                        try {
                                            const json = JSON.parse(objStr);
                                            const candidate = json.candidates?.[0];
                                            let text = '';
                                            let reasoning = '';
                                            if (candidate?.content?.parts) {
                                                for (const part of candidate.content.parts) {
                                                    const isThoughtPart = part.thought === true || typeof part.thought === 'string';

                                                    if (isThoughtPart) {
                                                        if (typeof part.thought === 'string') {
                                                            reasoning += part.thought;
                                                        }
                                                        if (part.text) {
                                                            reasoning += part.text;
                                                        }
                                                    } else if (part.text) {
                                                        const chunkHasThought = candidate.content.parts.some((p: any) => p.thought === true);
                                                        if (chunkHasThought) {
                                                            reasoning += part.text;
                                                        } else {
                                                            text += part.text;
                                                        }
                                                    }
                                                    if (part.reasoning_content) reasoning += part.reasoning_content;
                                                }
                                            }

                                            // Handle citations from grounding metadata
                                            let citations: { title: string; url: string; source?: string }[] | undefined;
                                            const groundingMetadata = candidate?.groundingMetadata;
                                            if (groundingMetadata?.groundingChunks) {
                                                citations = groundingMetadata.groundingChunks
                                                    .map((chunk: any) => chunk.web ? {
                                                        title: chunk.web.title || 'Web Source',
                                                        url: chunk.web.uri,
                                                        source: 'Google'
                                                    } : null)
                                                    .filter(Boolean);
                                            }

                                            if (text || reasoning || citations) {
                                                onChunk({
                                                    content: text,
                                                    reasoning: reasoning || undefined,
                                                    citations: (citations && citations.length > 0) ? citations : undefined
                                                });
                                            }
                                        } catch (e) { }
                                        startIdx = i + 1;
                                    }
                                }
                            }
                            escape = char === '\\' && !escape;
                        }

                        if (startIdx > 0) {
                            buffer = buffer.substring(startIdx);
                        }
                    }

                    if (xhr.readyState === 4) {
                        // status === 0 means aborted
                        if (xhr.status === 0) {
                            resolve();
                            return;
                        }
                        if (xhr.status >= 200 && xhr.status < 300) {
                            resolve();
                        } else {
                            const err = new Error(`HTTP ${xhr.status}: ${xhr.responseText}`);
                            onError(err);
                            reject(err);
                        }
                        this.activeXhr = null;
                    }
                };

                xhr.onerror = () => {
                    const err = new Error('Network request failed');
                    onError(err);
                    reject(err);
                    this.activeXhr = null;
                };

                xhr.send(JSON.stringify(body));

            } catch (e) {
                onError(e as Error);
                reject(e);
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
