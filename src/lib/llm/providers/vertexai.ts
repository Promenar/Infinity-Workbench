import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { KJUR } from 'jsrsasign';

// Add global polyfill for TextEncoder if missing (RN environment)
if (typeof TextEncoder === 'undefined') {
    global.TextEncoder = require('text-encoding').TextEncoder;
}

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
    private serviceAccountJson: any;

    constructor(
        // Allow passing either the full config object OR a service account JSON (legacy/alternative param style)
        configOrJson: {
            apiKey: string;
            model: string;
            temperature: number;
            baseUrl: string;
            project?: string;
            location?: string;
            keyJson?: string;
        } | any,
        model?: string,
        temperature?: number,
        location: string = 'us-central1'
    ) {
        // Handle dual constructor signature for backward compatibility
        if (model !== undefined && temperature !== undefined) {
            // Legacy signature: (serviceAccountJson, model, temperature, location)
            this.serviceAccountJson = configOrJson;
            this.model = model;
            this.temperature = temperature;
            this.location = location;
            this.apiKey = ''; // Not used when using service account json directly
            this.baseUrl = ''; // Derived from location
            this.project = this.serviceAccountJson.project_id;
        } else {
            // Config object signature
            const config = configOrJson;
            this.apiKey = config.apiKey;
            this.baseUrl = config.baseUrl;
            this.model = config.model;
            this.temperature = config.temperature;
            this.project = config.project;
            this.location = config.location;
            this.keyJson = config.keyJson;
        }
    }

    private async getAccessToken(): Promise<string> {
        // Use cached token if valid (with 5m buffer)
        if (this.accessToken && Date.now() < this.tokenExpiry - 300000) {
            return this.accessToken;
        }

        // 1. If we have serviceAccountJson (from Constructor A), use it
        if (this.serviceAccountJson) {
            return this.mintToken(this.serviceAccountJson);
        }

        // 2. If we have keyJson (from Constructor B settings), parse and use it
        if (this.keyJson) {
            const keyData = JSON.parse(this.keyJson);
            return this.mintToken(keyData);
        }

        // 3. Fallback to API Key if no JSON provided (limited scope)
        return this.apiKey;
    }

    private async mintToken(keyData: any): Promise<string> {
        try {
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

            // Rule 8.4: Capture HTML error pages
            const contentType = res.headers.get('Content-Type') || '';
            if (!contentType.includes('application/json')) {
                const text = await res.text();
                if (text.trim().startsWith('<')) {
                    throw new Error(`Received HTML error page instead of JSON for auth token.`);
                }
                throw new Error(`Unexpected Content-Type for auth: ${contentType}`);
            }

            const data = await res.json();
            this.accessToken = data.access_token;
            this.tokenExpiry = Date.now() + (data.expires_in * 1000);
            return this.accessToken!;
        } catch (e) {
            console.warn('Vertex AI Auth Error:', e);
            throw new Error(`Authentication failed: ${(e as Error).message}`);
        }
    }

    async chatCompletion(messages: ChatMessage[], options?: any): Promise<string> {
        let result = '';
        await this.streamChat(
            messages,
            (chunk: any) => {
                const content = typeof chunk === 'string' ? chunk : chunk.content;
                if (content) result += content;
            },
            (err) => {
                // Don't throw here! It runs in XHR callback and causes crash.
                // allow streamChat promise rejection to handle it.
                console.warn('[VertexAiClient] Stream error:', err.message);
            },
            options
        );
        return result;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: { content: string; reasoning?: string; citations?: { title: string; url: string; source?: string }[] }) => void,
        onError: (err: Error) => void,
        options?: ChatMessageOptions
    ): Promise<void> {
        return new Promise(async (resolve, reject) => {
            try {
                // Ensure we have a project ID
                const projectId = this.project || this.serviceAccountJson?.project_id;
                if (!projectId) throw new Error('Project ID is required');

                // 1. Get Authentication
                const token = await this.getAccessToken().catch(e => {
                    throw e;
                });

                // 2. Resolve Endpoint
                const region = this.location || 'us-central1';
                const host = region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

                // Use v1beta1 to support preview models and newer features
                const endpoint = `https://${host}/v1beta1/projects/${projectId}/locations/${region}/publishers/google/models/${this.model}:streamGenerateContent`;

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
                        temperature: options?.inferenceParams?.temperature ?? (this.temperature || 0.7),
                        topP: options?.inferenceParams?.topP,
                        maxOutputTokens: options?.inferenceParams?.maxTokens,
                        ...(options?.reasoning && !this.model.includes('image') ? {
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

                // Queue to ensure strict order of chunks and async image writes
                let processingQueue = Promise.resolve();
                const enqueue = (task: () => void | Promise<void>) => {
                    processingQueue = processingQueue.then(task).catch(e => console.warn('Stream processing error:', e));
                };

                xhr.onreadystatechange = () => {
                    if (xhr.readyState === 3 || xhr.readyState === 4) {
                        const newText = xhr.responseText.substring(lastPosition);
                        lastPosition = xhr.responseText.length;
                        buffer += newText;

                        // Robust JSON stream parsing
                        let startIdx = 0;
                        let bracketCount = 0;
                        let insideString = false;

                        for (let i = 0; i < buffer.length; i++) {
                            const char = buffer[i];

                            if (insideString) {
                                // ⚡ FAST PATH: Jump to next quote
                                const nextQuote = buffer.indexOf('"', i);
                                if (nextQuote === -1) {
                                    i = buffer.length;
                                    break;
                                }

                                // Check for escape char
                                let backslashCount = 0;
                                let j = nextQuote - 1;
                                while (j >= i && buffer[j] === '\\') {
                                    backslashCount++;
                                    j--;
                                }

                                i = nextQuote;
                                if (backslashCount % 2 === 0) {
                                    insideString = false;
                                }
                                continue;
                            }

                            if (char === '"') {
                                insideString = true;
                            } else if (char === '{') {
                                if (bracketCount === 0) startIdx = i;
                                bracketCount++;
                            } else if (char === '}') {
                                bracketCount--;
                                if (bracketCount === 0) {
                                    const objStr = buffer.substring(startIdx, i + 1);

                                    // Process this JSON object
                                    enqueue(async () => {
                                        try {
                                            const json = JSON.parse(objStr);
                                            const responseObj = Array.isArray(json) ? json[0] : json;
                                            const candidate = responseObj.candidates?.[0];

                                            if (candidate) {
                                                let text = '';
                                                let reasoning = '';
                                                let images: { mime: string, uri: string }[] = [];

                                                if (candidate.content?.parts) {
                                                    for (const part of candidate.content.parts) {
                                                        const isThoughtPart = part.thought === true || typeof part.thought === 'string';

                                                        // 1. Thinking
                                                        if (isThoughtPart) {
                                                            if (typeof part.thought === 'string') reasoning += part.thought;
                                                            if (part.text) reasoning += part.text;
                                                        }
                                                        // 2. Text
                                                        else if (part.text) {
                                                            const chunkHasThought = candidate.content.parts.some((p: any) => p.thought === true);
                                                            if (chunkHasThought) {
                                                                reasoning += part.text;
                                                            } else {
                                                                text += part.text;
                                                            }
                                                        }
                                                        // 3. Images (inlineData)
                                                        else if (part.inlineData) {
                                                            // Extract Base64 image data
                                                            const { mimeType, data } = part.inlineData;

                                                            // IMPORTANT: Save to filesystem to avoid performance issues
                                                            // Large Base64 data URIs (several MB) cause severe lag
                                                            try {
                                                                // Lazy import to avoid circular dependency
                                                                const { saveBase64ToFile, generateThumbnail } = require('../../image-utils');

                                                                // Save original
                                                                const originalUri = await saveBase64ToFile(data, 'originals', mimeType);

                                                                // Generate thumbnail for faster display
                                                                const thumbnailUri = await generateThumbnail(originalUri, {
                                                                    maxWidth: 512,
                                                                    compress: 0.75
                                                                });

                                                                // Use thumbnail for chat (much smaller)
                                                                images.push({
                                                                    mime: mimeType,
                                                                    uri: thumbnailUri
                                                                });

                                                                console.log('[VertexAI] Saved to file:', thumbnailUri.substring(0, 80));
                                                            } catch (error) {
                                                                console.error('[VertexAI] File save failed:', error);
                                                                // Fallback: data URI (will lag)
                                                                const dataUri = `data:${mimeType};base64,${data}`;
                                                                images.push({ mime: mimeType, uri: dataUri });
                                                            }
                                                        }
                                                    }
                                                }

                                                // Append images to text as local file links
                                                for (const img of images) {
                                                    // Use file:// URI directly in markdown
                                                    text += `\n\n![Generated Image](${img.uri})\n\n`;
                                                }

                                                let citations: { title: string; url: string; source?: string }[] | undefined;
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
                                        } catch (e) {
                                            // JSON parse error or other logic error
                                            console.error('Stream processing error', e);
                                        }
                                    });

                                    startIdx = i + 1;
                                }
                            }
                        }
                        if (startIdx > 0) buffer = buffer.substring(startIdx);
                    }

                    if (xhr.readyState === 4) {
                        // Wait for all processing to finish before resolving
                        enqueue(() => {
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
                        });
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
            const projectId = this.project || this.serviceAccountJson?.project_id;
            if (!projectId) throw new Error('Project ID is required (settings)');

            const token = await this.getAccessToken();

            const region = this.location || 'us-central1';
            const host = region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

            // Use v1beta1
            const endpoint = `https://${host}/v1beta1/projects/${projectId}/locations/${region}/publishers/google/models/${this.model}:generateContent`;

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
}
