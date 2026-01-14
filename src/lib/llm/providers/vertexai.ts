import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { KJUR } from 'jsrsasign';
import { Skill, ToolCall, ToolResult } from '../../../types/skills';
import { apiLogger } from '../api-logger';
import zodToJsonSchema from 'zod-to-json-schema/dist/cjs/index.js';

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
      console.warn('Vertex AI Auth Error (Network/Auth):', e);
      // 🔥 CRITICAL: Do NOT throw here if we are in a background process like KG extraction.
      // Returning null or empty string will bubble up to getAccessToken which will use the existing (possibly expired) token or fallback.
      // But more importantly, it avoids RED BOX in React Native.
      return '';
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
      function_declarations: skills.map((skill) => {
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
      thought_signature?: string;
      citations?: { title: string; url: string; source?: string }[];
      toolCalls?: ToolCall[];
      usage?: { input: number; output: number; total: number };
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
        const formatContentPart = (m: ChatMessage) => {
          // 1. 处理工具执行结果 (Role: tool)
          if (m.role === 'tool') {
            const part: any = {
              functionResponse: {
                name: m.name || 'unknown',
                response: {
                  content: m.content
                }
              }
            };
            // 🧐 CRITICAL: thought_signature is a PART-level field, not nested in functionResponse
            if (m.thought_signature) {
              part.thought_signature = m.thought_signature;
            }
            return [part];
          }

          // 2. 处理包含工具调用的助手消息 或 包含思考的消息
          if (m.role === 'assistant') {
            const parts: any[] = [];
            // 🧐 CRITICAL: Gemini 2.0 Thinking models match thoughts by signature.
            if (m.reasoning) {
              parts.push({
                thought: true,
                text: m.reasoning,
                ...(m.thought_signature ? { thought_signature: m.thought_signature } : {})
              });
            }
            if (m.content && typeof m.content === 'string') {
              parts.push({ text: m.content });
            }

            if (m.tool_calls && m.tool_calls.length > 0) {
              m.tool_calls.forEach((tc: any) => {
                // 🧐 CRITICAL: Support both native tc.name and OpenAI-style tc.function.name
                const name = tc.name || tc.function?.name;
                if (!name) return;

                const part: any = {
                  functionCall: {
                    name: name,
                    args: typeof tc.arguments === 'string'
                      ? JSON.parse(tc.arguments)
                      : (tc.function?.arguments
                        ? (typeof tc.function.arguments === 'string' ? JSON.parse(tc.function.arguments) : tc.function.arguments)
                        : (tc.arguments || {}))
                  }
                };
                if (m.thought_signature) {
                  part.thought_signature = m.thought_signature;
                }
                parts.push(part);
              });
            }
            return parts;
          }

          // 3. 处理普通用户文本或多模态消息
          const parts: any[] = [];
          if (typeof m.content === 'string') {
            parts.push({ text: m.content });
          } else if (Array.isArray(m.content)) {
            m.content.forEach((c: any) => {
              if (c.type === 'text') parts.push({ text: c.text });
              if (c.type === 'image_url') {
                const matches = c.image_url.url.match(/^data:(.+);base64,(.+)$/);
                if (matches) {
                  parts.push({
                    inlineData: {
                      mimeType: matches[1],
                      data: matches[2],
                    },
                  });
                }
              }
            });
          }

          return parts;
        };

        // 🛡️ Pre-calculate System Instruction
        let combinedSystemTitle = '';
        messages.forEach(m => {
          if (m.role === 'system') {
            combinedSystemTitle += (combinedSystemTitle ? '\n' : '') + m.content;
          }
        });

        // System Nudge for Voice/Tool consistency
        const hasTools = (options?.skills && options.skills.length > 0) || options?.webSearch;
        const toolGuidance = hasTools
          ? `\nYou are a helpful assistant with access to tools. 
CRITICAL RULES:
1. You MUST use the native function calling mechanism to execute tools. DO NOT just write code blocks or descriptions of tool calls.
2. If you need information, call 'query_vector_db' or 'search_internet' IMMEDIATELY.
3. If you need to generate an image, call 'generate_image' IMMEDIATELY.
4. DO NOT say "I will search for..." or "I am generating...", just CALL THE FUNCTION.
5. You can call multiple tools if needed.
Available tools: ${options?.skills?.map((s: any) => s.id).join(', ') || 'N/A'}.`
          : '';

        const finalSystemInstruction = combinedSystemTitle + toolGuidance;

        // 🔍 Debug: Log thought_signature status for all messages
        console.log('[VertexAI] Formatting messages for API:');
        messages.forEach((m, idx) => {
          console.log(`  [${idx}] role=${m.role}, has_signature=${!!m.thought_signature}, has_tool_calls=${!!(m as any).tool_calls}`);
        });

        // 🔑 CRITICAL: Vertex AI requiring strict alternating user/model turns.
        const normalizedTurns: { role: 'user' | 'model', parts: any[] }[] = [];

        messages.forEach((m) => {
          if (m.role === 'system') return; // Skip, already handled in systemInstruction

          const currentParts = formatContentPart(m);
          if (currentParts.length === 0) return;

          if (m.role === 'user' || m.role === 'tool') {
            const lastTurn = normalizedTurns[normalizedTurns.length - 1];
            if (lastTurn && lastTurn.role === 'user') {
              lastTurn.parts.push(...currentParts);
            } else {
              normalizedTurns.push({ role: 'user', parts: currentParts });
            }
          } else if (m.role === 'assistant') {
            const lastTurn = normalizedTurns[normalizedTurns.length - 1];
            if (lastTurn && lastTurn.role === 'model') {
              lastTurn.parts.push(...currentParts);
            } else {
              normalizedTurns.push({ role: 'model', parts: currentParts });
            }
          }
        });

        // 🛡️ Ensure contents starts with 'user'
        if (normalizedTurns.length > 0 && normalizedTurns[0].role === 'model') {
          normalizedTurns.unshift({ role: 'user', parts: [{ text: 'Please proceed.' }] });
        }

        console.log('[VertexAI] Normalized Turns:', normalizedTurns.length);
        normalizedTurns.forEach((t, i) => {
          console.log(`  Turn ${i}: ${t.role} (${t.parts.length} parts)`);
        });

        const body: any = {
          contents: normalizedTurns,
          generation_config: {
            temperature: options?.inferenceParams?.temperature ?? (this.temperature || 0.7),
            top_p: options?.inferenceParams?.topP,
            max_output_tokens: options?.inferenceParams?.maxTokens,
            ...(options?.reasoning && !this.model.includes('image')
              ? {
                thinking_config: {
                  include_thoughts: true,
                },
              }
              : {}),
          },
        };

        if (finalSystemInstruction) {
          body.system_instruction = {
            parts: [{ text: finalSystemInstruction }]
          };
        }

        const tools: any[] = [];
        if (options?.webSearch) {
          tools.push({ google_search_retrieval: {} });
        }

        if (hasTools && options?.skills && options.skills.length > 0) {
          const geminiTools = this.mapSkillsToGeminiTools(options.skills);
          tools.push(...geminiTools);

          body.tool_config = {
            function_calling_config: {
              mode: 'AUTO'
            }
          };
        }

        if (tools.length > 0) {
          body.tools = tools;
        }

        // 📝 Debug Logging
        console.log('[VertexAI] Request Body (Partial):', JSON.stringify({
          has_system: !!body.system_instruction,
          has_tools: !!body.tools,
          tool_count: body.tools?.[0]?.function_declarations?.length || 0,
          turns: body.contents.length
        }));

        // 📝 Debug Logging
        // 🔍 Debug: Log final contents structure (Increased for full history visibility)
        console.log('[VertexAI] Final contents:', JSON.stringify(body.contents, null, 2).substring(0, 10000));

        apiLogger.logRequest('vertexai', endpoint, body);

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
                      // 🧐 Vertex AI often sends an array of objects in one stream chunk, or single objects.
                      // We must handle both.
                      const responses = Array.isArray(json) ? json : [json];

                      for (const responseObj of responses) {
                        const candidate = responseObj.candidates?.[0];

                        if (candidate) {
                          // 🧐 Capture thought_signature from candidate top-level or parts
                          // Some versions use snake_case, some use camelCase (Vertex vs Gemini API)
                          const candidateSignature = candidate.thought_signature || candidate.thoughtSignature;
                          if (candidateSignature) {
                            console.log('[VertexAI] Found signature in candidate root:', candidateSignature);
                            onChunk({ content: '', thought_signature: candidateSignature });
                          }

                          let text = '';
                          let reasoning = '';
                          let images: { mime: string; uri: string }[] = [];
                          let toolCalls: ToolCall[] = [];
                          if (candidate.content?.parts) {

                            for (const part of candidate.content.parts) {
                              // 🔍 Debug: Log full part structure to find missing signature
                              console.log('[VertexAI] Raw Part:', JSON.stringify(part));

                              // 🧐 核心修复：更鲁棒的捕捉方式
                              const signature = part.thought_signature || part.thoughtSignature;
                              if (signature) {
                                console.log('[VertexAI] FOUND SIGNATURE:', signature);
                                onChunk({ content: '', thought_signature: signature });
                              }

                              const isThoughtPart =
                                !!part.thought || // Handles true, "true", or non-empty string
                                typeof part.thought === 'string';

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
                              // 3. Function Call (Native Gemini)
                              else if (part.functionCall) {
                                const tcId = `vcall_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
                                toolCalls.push({
                                  id: tcId,
                                  name: part.functionCall.name,
                                  arguments: part.functionCall.args || {}
                                });

                                // Signature capture moved to top of loop for robustness
                              }
                              // 4. Images (inlineData)
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
                                  console.warn('[VertexAI] File save failed (Silenced):', error);
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

                          if (text || reasoning || (citations && citations.length > 0) || (toolCalls && toolCalls.length > 0)) {
                            onChunk({
                              content: text,
                              reasoning: reasoning || undefined,
                              citations: citations && citations.length > 0 ? citations : undefined,
                              toolCalls: toolCalls.length > 0 ? toolCalls : undefined
                            });
                          }
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
                // Log full response if available (usually stream chunks are caught in logic below)
                // but for completion non-stream it helps
                apiLogger.logResponse('vertexai', xhr.status, xhr.responseText);
                resolve();
              } else {
                apiLogger.logResponse('vertexai', xhr.status, xhr.responseText);
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
