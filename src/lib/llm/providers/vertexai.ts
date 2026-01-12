import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { KJUR } from 'jsrsasign';
import { Skill, ToolCall } from '../../../types/skills';
import zodToJsonSchema from 'zod-to-json-schema';

// Add global polyfill for TextEncoder if missing (RN environment)
if (typeof TextEncoder === 'undefined') {
  global.TextEncoder = require('text-encoding').TextEncoder;
}

export class VertexAiClient implements LlmClient {
  private apiKey: string;
  // ... (lines 11-154 preserved implicitly by replace logic? No, this is replace_file_content, I must be precise with range)

  // Let's use a multi-replace to minimize context management or do a precise insertion.
  // The file is huge. It's safer to use smaller chunks.
  // I will cancel this tool call and use multi_replace_file_content to insert imports and the method.

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
    configOrJson:
      | {
        apiKey: string;
        model: string;
        temperature: number;
        baseUrl: string;
        project?: string;
        location?: string;
        keyJson?: string;
      }
      | any,
    model?: string,
    temperature?: number,
    location: string = 'us-central1',
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
        iat: now,
      };

      const sHeader = JSON.stringify(header);
      const sClaim = JSON.stringify(claim);
      const sJWS = KJUR.jws.JWS.sign(null, sHeader, sClaim, keyData.private_key);

      const res = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${sJWS}`,
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
      this.tokenExpiry = Date.now() + data.expires_in * 1000;
      return this.accessToken!;
    } catch (e) {
      console.warn('Vertex AI Auth Error:', e);
      throw new Error(`Authentication failed: ${(e as Error).message}`);
    }
  }

  private mapSkillsToGeminiTools(skills: Skill[]): any[] {
    // Helper to recursively uppercase types in schema for Gemini strictness
    const fixSchema = (s: any): any => {
      if (!s || typeof s !== 'object') return s;
      const result = { ...s };
      if (result.type && typeof result.type === 'string') {
        result.type = result.type.toUpperCase();
      }
      if (result.properties) {
        const newProps: any = {};
        for (const key in result.properties) {
          newProps[key] = fixSchema(result.properties[key]);
        }
        result.properties = newProps;
      }
      if (result.items) {
        result.items = fixSchema(result.items);
      }
      return result;
    };

    return [{
      functionDeclarations: skills.map((skill) => {
        let schema = zodToJsonSchema(skill.schema as any) as any;
        schema = JSON.parse(JSON.stringify(schema)); // Deep clone to avoid mutation issues

        delete schema.$schema;
        delete schema.additionalProperties;

        // CRITICAL FIX: Vertex AI strictly requires 'type' to be 'OBJECT' for parameters
        if (!schema.type) {
          schema.type = 'OBJECT';
        } else {
          schema.type = schema.type.toUpperCase();
        }

        // Apply recursive fix to properties
        schema = fixSchema(schema);

        return {
          name: skill.id,
          description: skill.description,
          parameters: schema,
        };
      })
    }];
  }

  async chatCompletion(
    messages: ChatMessage[],
    options?: any,
  ): Promise<{ content: string; usage?: { input: number; output: number; total: number } }> {
    let result = '';
    let finalUsage: { input: number; output: number; total: number } | undefined;

    await this.streamChat(
      messages,
      (chunk: any) => {
        const content = typeof chunk === 'string' ? chunk : chunk.content;
        if (content) result += content;
        if (chunk.usage) finalUsage = chunk.usage;
      },
      (err) => {
        // Don't throw here! It runs in XHR callback and causes crash.
        // allow streamChat promise rejection to handle it.
        console.warn('[VertexAiClient] Stream error:', err.message);
      },
      options,
    );
    return { content: result, usage: finalUsage };
  }

  async streamChat(
    messages: ChatMessage[],
    onChunk: (chunk: {
      content: string;
      reasoning?: string;
      citations?: { title: string; url: string; source?: string }[];
    }) => void,
    onError: (err: Error) => void,
    options?: ChatMessageOptions,
  ): Promise<void> {
    return new Promise(async (resolve, reject) => {
      try {
        // Ensure we have a project ID
        const projectId = this.project || this.serviceAccountJson?.project_id;
        if (!projectId) throw new Error('Project ID is required');

        // 1. Get Authentication
        const token = await this.getAccessToken().catch((e) => {
          throw e;
        });

        // 2. Resolve Endpoint
        const region = this.location || 'us-central1';
        const host =
          region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

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
            const parts = m.content
              .map((c: any) => {
                if (c.type === 'text') return { text: c.text };
                if (c.type === 'image_url') {
                  // Extract base64 from data URI
                  const matches = c.image_url.url.match(/^data:(.+);base64,(.+)$/);
                  if (matches) {
                    return {
                      inline_data: {
                        mime_type: matches[1],
                        data: matches[2],
                      },
                    };
                  }
                }
                return null;
              })
              .filter(Boolean);
            return { parts };
          }
          return { parts: [] };
        };

        // System Nudge for Voice/Tool consistency
        const hasTools = (options?.skills && options.skills.length > 0) || options?.webSearch;
        const systemInstruction = hasTools
          ? `You are a helpful assistant with access to tools. 
CRITICAL RULES:
1. You MUST use the native function calling mechanism to execute tools. DO NOT just write code blocks or descriptions of tool calls.
2. If you need information, call 'query_vector_db' or 'search_internet' IMMEDIATELY.
3. If you need to generate an image, call 'generate_image' IMMEDIATELY.
4. DO NOT say "I will search for..." or "I am generating...", just CALL THE FUNCTION.
5. You can call multiple tools if needed.
Available tools: ${options.skills?.map((s: any) => s.id).join(', ') || 'N/A'}.`
          : undefined;

        const body: any = {
          contents: messages.map((m) => {
            let role = m.role === 'assistant' ? 'model' : 'user';
            if (m.role === 'tool') role = 'function';
            return {
              role,
              ...formatMessage(m),
            };
          }),
          generationConfig: {
            temperature: options?.inferenceParams?.temperature ?? (this.temperature || 0.7),
            topP: options?.inferenceParams?.topP,
            maxOutputTokens: options?.inferenceParams?.maxTokens,
            ...(options?.reasoning && !this.model.includes('image')
              ? {
                thinkingConfig: {
                  includeThoughts: true,
                },
              }
              : {}),
          },
        };

        if (systemInstruction) {
          body.systemInstruction = {
            parts: [{ text: systemInstruction }]
          };
        }

        const tools: any[] = [];

        if (options?.webSearch) {
          tools.push({ googleSearch: {} });
        }

        if (hasTools && options?.skills && options.skills.length > 0) {
          const geminiTools = this.mapSkillsToGeminiTools(options.skills);
          tools.push(...geminiTools);

          body.toolConfig = {
            functionCallingConfig: {
              mode: 'AUTO'
            }
          };
        }

        if (tools.length > 0) {
          body.tools = tools;
        }

        let lastPosition = 0;
        let buffer = '';

        // Queue to ensure strict order of chunks and async image writes
        let processingQueue = Promise.resolve();
        const enqueue = (task: () => void | Promise<void>) => {
          processingQueue = processingQueue
            .then(task)
            .catch((e) => console.warn('Stream processing error:', e));
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
                        let images: { mime: string; uri: string }[] = [];

                        if (candidate.content?.parts) {
                          for (const part of candidate.content.parts) {
                            const isThoughtPart =
                              part.thought === true || typeof part.thought === 'string';

                            // 1. Thinking
                            if (isThoughtPart) {
                              if (typeof part.thought === 'string') reasoning += part.thought;
                              if (part.text) reasoning += part.text;
                            }
                            // 2. Text
                            else if (part.text) {
                              const chunkHasThought = candidate.content.parts.some(
                                (p: any) => p.thought === true,
                              );
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
                                const {
                                  saveBase64ToFile,
                                  generateThumbnail,
                                } = require('../../image-utils');

                                // Save original
                                const originalUri = await saveBase64ToFile(
                                  data,
                                  'originals',
                                  mimeType,
                                );

                                // Generate thumbnail for faster display
                                const thumbnailUri = await generateThumbnail(originalUri, {
                                  maxWidth: 512,
                                  compress: 0.75,
                                });

                                // Use thumbnail for chat (much smaller)
                                images.push({
                                  mime: mimeType,
                                  uri: thumbnailUri,
                                });

                                console.log(
                                  '[VertexAI] Saved to file:',
                                  thumbnailUri.substring(0, 80),
                                );
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

                        let citations:
                          | { title: string; url: string; source?: string }[]
                          | undefined;
                        const groundingMetadata =
                          candidate.groundingMetadata || responseObj.groundingMetadata;

                        if (groundingMetadata?.groundingChunks) {
                          citations = groundingMetadata.groundingChunks
                            .map((chunk: any) =>
                              chunk.web
                                ? {
                                  title: chunk.web.title || 'Web Source',
                                  url: chunk.web.uri,
                                  source: 'Google',
                                }
                                : null,
                            )
                            .filter(Boolean);
                        }

                        if (text || reasoning || (citations && citations.length > 0)) {
                          onChunk({
                            content: text,
                            reasoning: reasoning || undefined,
                            citations: citations && citations.length > 0 ? citations : undefined,
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
                const err = new Error(
                  `Vertex API Error (${xhr.status}): ${xhr.responseText.substring(0, 200)}`,
                );
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
      const host =
        region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

      // 检测是否为 Embedding 模型
      const isEmbeddingModel =
        this.model.toLowerCase().includes('embedding') ||
        this.model.toLowerCase().includes('embed');

      let endpoint: string;
      let body: any;

      if (isEmbeddingModel) {
        // Embedding 模型使用 :predict 端点
        endpoint = `https://${host}/v1/projects/${projectId}/locations/${region}/publishers/google/models/${this.model}:predict`;
        body = {
          instances: [{ content: 'test' }],
        };
      } else {
        // 聊天模型使用 :generateContent 端点
        endpoint = `https://${host}/v1beta1/projects/${projectId}/locations/${region}/publishers/google/models/${this.model}:generateContent`;
        body = {
          contents: [{ role: 'user', parts: [{ text: 'ping' }] }],
          generationConfig: { maxOutputTokens: 1 },
        };
      }

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(body),
      });

      const latency = Date.now() - start;

      if (!response.ok) {
        // Rule 8.4: Capture HTML error pages
        const contentType = response.headers.get('Content-Type') || '';
        const errorText = await response.text();

        if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
          return {
            success: false,
            latency,
            error: `HTTP ${response.status}: Received non-JSON response (possibly HTML error page).`,
          };
        }

        return {
          success: false,
          latency,
          error: `HTTP ${response.status}: ${errorText.substring(0, 100)}`,
        };
      }

      return { success: true, latency };
    } catch (e) {
      const latency = Date.now() - start;
      return { success: false, latency, error: (e as Error).message };
    }
  }
  async generateImage(
    prompt: string,
    options?: { size?: string },
  ): Promise<{ url: string; revisedPrompt?: string }> {
    const projectId = this.project || this.serviceAccountJson?.project_id;
    if (!projectId) throw new Error('Project ID is required for Vertex AI Image Generation');

    const token = await this.getAccessToken();
    const region = this.location || 'us-central1';
    const host =
      region === 'global' ? 'aiplatform.googleapis.com' : `${region}-aiplatform.googleapis.com`;

    const isGemini = this.model.startsWith('gemini-');

    // Choose endpoint and version based on model type
    // Gemini models on Vertex usually need v1beta1 and :generateContent
    // Imagen models use v1/v2 and :predict
    const version = isGemini ? 'v1beta1' : 'v1';
    const method = isGemini ? 'generateContent' : 'predict';
    const endpoint = `https://${host}/${version}/projects/${projectId}/locations/${region}/publishers/google/models/${this.model}:${method}`;

    let body: any;
    if (isGemini) {
      body = {
        contents: [{ role: 'user', parts: [{ text: prompt }] }],
        generationConfig: {
          // Some Gemini models (like 3-pro-image) might have specific config needed for images
          // but usually generateContent with direct prompt works if it's an image model.
        }
      };
    } else {
      body = {
        instances: [{ prompt }],
        parameters: {
          sampleCount: 1,
        },
      };
    }

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Vertex AI Image Error (${response.status}): ${errorText}`);
    }

    const data = await response.json();
    let base64Data: string | undefined;
    let mimeType = 'image/png';

    if (isGemini) {
      const candidate = data.candidates?.[0];
      const part = candidate?.content?.parts?.find((p: any) => p.inlineData);
      if (part?.inlineData) {
        base64Data = part.inlineData.data;
        mimeType = part.inlineData.mimeType || 'image/png';
      }
    } else {
      if (data.predictions && data.predictions.length > 0) {
        const prediction = data.predictions[0];
        base64Data = prediction.bytesBase64Encoded;
      }
    }

    if (!base64Data) {
      throw new Error('Invalid response format or no image generated from Vertex AI');
    }

    // Persist to file to avoid lag
    try {
      const { saveBase64ToFile, generateThumbnail } = require('../../image-utils');
      const originalUri = await saveBase64ToFile(base64Data, 'originals', mimeType);
      const thumbnailUri = await generateThumbnail(originalUri, { maxWidth: 1024, compress: 0.8 });
      return { url: thumbnailUri };
    } catch (e) {
      console.warn('[VertexAI] Failed to save image to file, falling back to data URI', e);
      return { url: `data:${mimeType};base64,${base64Data}` };
    }
  }
}
