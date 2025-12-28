import { LlmClient, ChatMessage } from '../types';
import { KJUR } from 'jsrsasign';

export class VertexAiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;
    private project?: string;
    private location?: string;
    private keyJson?: string;
    private activeXhr: XMLHttpRequest | null = null;
    private accessToken: string | null = null;
    private tokenExpiry: number = 0;

    constructor(config: {
        apiKey: string;
        model: string;
        temperature: number;
        baseUrl: string;
        project?: string;
        location?: string;
        keyJson?: string;
    }) {
        this.apiKey = config.apiKey;
        this.baseUrl = config.baseUrl;
        this.model = config.model;
        this.temperature = config.temperature;
        this.project = config.project;
        this.location = config.location;
        this.keyJson = config.keyJson;
    }

    private async getAccessToken(): Promise<string> {
        // Use cached token if valid (with 5m buffer)
        if (this.accessToken && Date.now() < this.tokenExpiry - 300000) {
            return this.accessToken;
        }

        if (!this.keyJson) {
            // Fallback to API Key if no JSON provided (rare for Vertex but possible)
            return this.apiKey;
        }

        try {
            const keyData = JSON.parse(this.keyJson);
            const now = Math.floor(Date.now() / 1000);

            const header = { alg: 'RS256', typ: 'JWT' };
            const claim = {
                iss: keyData.client_email,
                scope: 'https://www.googleapis.com/auth/cloud-platform',
                aud: 'https://oauth2.googleapis.com/token',
                exp: now + 3600,
                iat: now
            };

            const sHeader = JSON.stringify(header);
            const sClaim = JSON.stringify(claim);
            const sJWS = KJUR.jws.JWS.sign(null, sHeader, sClaim, keyData.private_key);

            const res = await fetch('https://oauth2.googleapis.com/token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${sJWS}`
            });

            if (!res.ok) {
                const errText = await res.text();
                throw new Error(`Token exchange failed: ${res.status} ${errText}`);
            }

            const data = await res.json();
            this.accessToken = data.access_token;
            this.tokenExpiry = Date.now() + (data.expires_in * 1000);
            return this.accessToken!;
        } catch (e) {
            console.error('Vertex AI Auth Error:', e);
            throw new Error(`Authentication failed: ${(e as Error).message}`);
        }
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[] }) => void,
        onError: (err: Error) => void,
        options?: { webSearch?: boolean; reasoning?: boolean }
    ): Promise<void> {
        return new Promise(async (resolve, reject) => {
            try {
                if (!this.project) throw new Error('Project ID is required');

                // 1. Get Authentication
                const token = await this.getAccessToken().catch(e => {
                    throw e;
                });

                // 2. Resolve Endpoint
                const region = this.location || 'us-central1';
                const host = region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

                // Use v1beta1 to support preview models and newer features
                const endpoint = `https://${host}/v1beta1/projects/${this.project}/locations/${region}/publishers/google/models/${this.model}:streamGenerateContent`;

                this.activeXhr = new XMLHttpRequest();
                const xhr = this.activeXhr;
                xhr.open('POST', endpoint);
                xhr.setRequestHeader('Content-Type', 'application/json');
                xhr.setRequestHeader('Authorization', `Bearer ${token}`);

                // 3. Prepare Payload
                const formatMessage = (m: ChatMessage) => {
                    if (typeof m.content === 'string') {
                        return { parts: [{ text: m.content }] };
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
                        return { parts };
                    }
                    return { parts: [] };
                };

                const body: any = {
                    contents: messages.map(m => ({
                        role: m.role === 'assistant' ? 'model' : 'user',
                        ...formatMessage(m)
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

                        // Robust JSON stream parsing
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
                                            // Handle Array of candidates or single object
                                            const responseObj = Array.isArray(json) ? json[0] : json;
                                            const candidate = responseObj.candidates?.[0];

                                            if (candidate) {
                                                let text = '';
                                                let reasoning = '';

                                                if (candidate.content?.parts) {
                                                    for (const part of candidate.content.parts) {
                                                        // In Gemini 2.0 Thinking/Flash Thinking:
                                                        // A part might have { text: "...", thought: true } or separate parts.
                                                        const isThoughtPart = part.thought === true || typeof part.thought === 'string';

                                                        if (isThoughtPart) {
                                                            if (typeof part.thought === 'string') {
                                                                reasoning += part.thought;
                                                            }
                                                            if (part.text) {
                                                                reasoning += part.text;
                                                            }
                                                        } else if (part.text) {
                                                            // If any part in this chunk has thought: true, 
                                                            // the text parts in this chunk are likely reasoning.
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

                                                let citations: { title: string; url: string; source?: string }[] | undefined;
                                                // groundingMetadata can be in candidate or at the top level
                                                const groundingMetadata = candidate.groundingMetadata || responseObj.groundingMetadata;

                                                if (groundingMetadata?.groundingChunks) {
                                                    citations = groundingMetadata.groundingChunks
                                                        .map((chunk: any) => chunk.web ? {
                                                            title: chunk.web.title || 'Web Source',
                                                            url: chunk.web.uri,
                                                            source: 'Google'
                                                        } : null)
                                                        .filter(Boolean);
                                                }

                                                if (text || reasoning || (citations && citations.length > 0)) {
                                                    onChunk({
                                                        content: text,
                                                        reasoning: reasoning || undefined,
                                                        citations: (citations && citations.length > 0) ? citations : undefined
                                                    });
                                                }
                                            }
                                        } catch (e) { }
                                        startIdx = i + 1;
                                    }
                                }
                            }
                            escape = char === '\\' && !escape;
                        }
                        if (startIdx > 0) buffer = buffer.substring(startIdx);
                    }
                    if (xhr.readyState === 4) {
                        if (xhr.status === 0) {
                            resolve(); // Aborted
                        } else if (xhr.status >= 200 && xhr.status < 300) {
                            resolve();
                        } else {
                            const err = new Error(`Vertex API Error (${xhr.status}): ${xhr.responseText.substring(0, 200)}`);
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
            if (!this.project) throw new Error('Project ID is required (settings)');

            const token = await this.getAccessToken();

            const region = this.location || 'us-central1';
            const host = region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

            // Use v1beta1
            const endpoint = `https://${host}/v1beta1/projects/${this.project}/locations/${region}/publishers/google/models/${this.model}:generateContent`;

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                },
                body: JSON.stringify({
                    contents: [{ role: 'user', parts: [{ text: 'ping' }] }],
                    generationConfig: { maxOutputTokens: 1 }
                }),
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
