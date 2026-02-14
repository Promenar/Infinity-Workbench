import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { supportsThinkingConfig } from '../model-utils';
import { Skill, ToolCall } from '../../../types/skills';
import { zodToJsonSchema } from 'zod-to-json-schema';
import * as FileSystem from 'expo-file-system/legacy'; // ✅ SDK54: 使用 legacy API 兼容 readAsStringAsync

export class GeminiClient implements LlmClient {
  private apiKey: string;
  private baseUrl: string;
  private model: string;
  private temperature: number;
  private activeXhr: XMLHttpRequest | null = null;

  constructor(apiKey: string, model: string, temperature: number, baseUrl?: string) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl || 'https://generativelanguage.googleapis.com';
    this.model = model;
    this.temperature = temperature;
  }

  async chatCompletion(
    messages: ChatMessage[],
    options?: any,
  ): Promise<{ content: string; toolCalls?: ToolCall[]; usage?: { input: number; output: number; total: number } }> {
    let result = '';
    let finalUsage: { input: number; output: number; total: number } | undefined;
    let finalToolCalls: ToolCall[] | undefined;

    await this.streamChat(
      messages,
      (chunk: any) => {
        const content = typeof chunk === 'string' ? chunk : chunk.content;
        if (content) result += content;
        if (chunk.usage) finalUsage = chunk.usage;
        if (chunk.toolCalls) finalToolCalls = chunk.toolCalls;
      },
      (err) => {
        throw err;
      },
      options,
    );
    return { content: result, toolCalls: finalToolCalls, usage: finalUsage };
  }

  private mapSkillsToGeminiTools(skills: Skill[]): any[] {
    // Helper to recursively normalize schema for Gemini/Vertex (Strict Schema Hardening)
    const normalizeGoogleSchema = (s: any): any => {
      if (!s || typeof s !== 'object') return s;
      const result = { ...s };

      // 1. Clean unsupported fields
      delete result.additionalProperties;
      delete result.title;
      delete result.default;
      delete result.$schema;
      delete result.pattern; // Regex validation not supported
      delete result.const;   // Const validation not supported

      // 2. Enforce Uppercase Types (Google Requirement)
      if (result.type && typeof result.type === 'string') {
        result.type = result.type.toUpperCase();
      }

      // 3. Recursive Traversal
      if (result.properties) {
        const newProps: any = {};
        for (const key in result.properties) {
          newProps[key] = normalizeGoogleSchema(result.properties[key]);
        }
        result.properties = newProps;
      }
      if (result.items) {
        result.items = normalizeGoogleSchema(result.items);
      }

      // 4. Ensure required is an array if present
      if (result.required && !Array.isArray(result.required)) {
        delete result.required;
      }

      return result;
    };

    return [{
      functionDeclarations: skills.map((skill) => {
        const schema = zodToJsonSchema(skill.schema as any) as any;
        // Deep clone to safely mutate
        const cleanSchema = JSON.parse(JSON.stringify(schema));

        return {
          name: skill.id,
          description: skill.description,
          parameters: normalizeGoogleSchema(cleanSchema),
        };
      })
    }];
  }

  async streamChat(
    messages: ChatMessage[],
    onChunk: (chunk: {
      content: string;
      reasoning?: string;
      citations?: { title: string; url: string; source?: string }[];
      toolCalls?: ToolCall[];
      usage?: { input: number; output: number; total: number };
    }) => void,
    onError: (err: Error) => void,
    options?: ChatMessageOptions,
  ): Promise<void> {
    return new Promise(async (resolve, reject) => {
      try {
        const cleanBase = this.baseUrl.replace(/\/v1$/, '').replace(/\/$/, '');
        const endpoint = `${cleanBase}/v1beta/models/${this.model}:streamGenerateContent?key=${this.apiKey}`;

        this.activeXhr = new XMLHttpRequest();
        const xhr = this.activeXhr;
        console.log('[GeminiClient] Opening XHR to:', endpoint.substring(0, 100));
        xhr.open('POST', endpoint);
        xhr.setRequestHeader('Content-Type', 'application/json');

        const formatMessage = async (m: ChatMessage) => {
          // Handle Tool Results (Role: tool)
          if (m.role === 'tool') {
            return {
              parts: [{
                functionResponse: {
                  name: m.name || 'unknown',
                  response: {
                    content: m.content
                  }
                }
              }]
            };
          }

          const parts: any[] = [];

          // Handle Assistant Messages with Tool Calls
          if (m.role === 'assistant' && m.tool_calls && m.tool_calls.length > 0) {
            // Some models require text to be first or accompanying tool calls
            const contentText = typeof m.content === 'string' ? m.content : '';
            const reasoningText = m.reasoning || '';
            const combinedText = [reasoningText, contentText].filter(Boolean).join('\n\n');

            if (combinedText) {
              parts.push({ text: combinedText });
            }

            m.tool_calls.forEach((tc: any) => {
              parts.push({
                functionCall: {
                  name: tc.function?.name || tc.name,
                  args: typeof tc.function?.arguments === 'string'
                    ? JSON.parse(tc.function.arguments)
                    : (tc.function?.arguments || tc.arguments || {})
                }
              });
            });
          }
          // Normal Text/Image Messages
          else if (typeof m.content === 'string') {
            const contentText = m.content;
            const reasoningText = m.reasoning || '';
            const combinedText = [reasoningText, contentText].filter(Boolean).join('\n\n');
            if (combinedText) parts.push({ text: combinedText });
          }
          else if (Array.isArray(m.content)) {
            m.content.forEach((c: any) => {
              if (c.type === 'text') parts.push({ text: c.text });
              if (c.type === 'image_url') {
                // Extract base64 from data URI
                const matches = (c.image_url.url as string).match(/^data:(.+);base64,(.+)$/);
                if (matches) {
                  parts.push({
                    inline_data: {
                      mime_type: matches[1],
                      data: matches[2],
                    },
                  });
                }
              }
            });
          }


          // ✅ Handle Native Files (Async)
          if (m.files && m.files.length > 0) {
            console.log(`[GeminiClient] Found ${m.files.length} files to attach.`);
            for (const file of m.files) {
              try {
                console.log(`[GeminiClient] Reading file: ${file.uri} (${file.mimeType})`);
                const base64 = await FileSystem.readAsStringAsync(file.uri, { encoding: 'base64' });
                console.log(`[GeminiClient] Read success, size: ${base64.length}`);

                parts.push({
                  inline_data: {
                    mime_type: file.mimeType,
                    data: base64
                  }
                });
                console.log(`[GeminiClient] Attached file: ${file.name}`);
              } catch (e) {
                console.error(`[GeminiClient] Failed to read file ${file.uri}:`, e);
                // Fallback: Add error message to parts so model knows
                parts.push({ text: `[System Error: Could not read file ${file.name}]` });
              }
            }
          } else {
            // console.log('[GeminiClient] No files found in message.');
          }

          return { parts };
        };

        // Safety: Only enable thinking config if model supports it and user enabled reasoning
        // CRITICAL: Gemini 2.0 Thinking models often fail when combined with tools in current API versions.
        // If tools (skills) are present, we might want to disable thinkingConfig or at least be careful.
        const hasTools = (options?.skills && options.skills.length > 0) || options?.webSearch;
        const shouldEnableThinking = options?.reasoning && supportsThinkingConfig(this.model) && !hasTools;

        // System Nudge for Voice/Tool consistency
        // 🆕 Phase 3 重构：始终使用原生搜索指导（与工具过滤逻辑一致）
        // Gemini 模型自主判断何时需要联网搜索，无需提示调用 search_internet
        const searchGuidance = '2. If you need current information, USE YOUR NATIVE SEARCH CAPABILITY directly. DO NOT call any search function.';

        const stopGuidance = '\n5. STOP searching once you have enough information to answer. DO NOT perform redundant searches if the current results allow for a complete answer.';

        // 始终从工具列表描述中排除 search_internet
        const toolListDesc = (options?.skills || [])
          .filter((s: any) => s.id !== 'search_internet')
          .map((s: any) => s.id)
          .join(', ') || 'N/A';

        const systemInstruction = hasTools
          ? `You are a helpful assistant with access to tools. 
CRITICAL RULES:
1. You MUST use the native function calling mechanism to execute tools. DO NOT just write code blocks or descriptions of tool calls.
${searchGuidance}
3. If you need to generate an image, call 'generate_image' IMMEDIATELY.
4. DO NOT say "I will search for..." or "I am generating...", just CALL THE FUNCTION or execute directly.${stopGuidance}
5. EXCLUSIVITY: DO NOT use the 'search_internet' tool. Always use your built-in native search capability instead.
Available tools: ${toolListDesc} + Native Web Search (built-in).`
          : undefined;

        // ✅ Async Message Processing
        const processedContents = [];
        for (const m of messages) {
          let role = m.role === 'assistant' ? 'model' : 'user';
          if (m.role === 'tool') role = 'function';

          const formatted = await formatMessage(m);
          processedContents.push({
            role,
            ...formatted
          });
        }

        let body: any = {
          contents: processedContents,
          safetySettings: [
            { category: 'HARM_CATEGORY_HARASSMENT', threshold: 'BLOCK_NONE' },
            { category: 'HARM_CATEGORY_HATE_SPEECH', threshold: 'BLOCK_NONE' },
            { category: 'HARM_CATEGORY_SEXUALLY_EXPLICIT', threshold: 'BLOCK_NONE' },
            { category: 'HARM_CATEGORY_DANGEROUS_CONTENT', threshold: 'BLOCK_NONE' },
            { category: 'HARM_CATEGORY_CIVIC_INTEGRITY', threshold: 'BLOCK_NONE' },
          ],
          generationConfig: {
            temperature: options?.inferenceParams?.temperature ?? (this.temperature || 0.7),
            topP: options?.inferenceParams?.topP,
            maxOutputTokens: options?.inferenceParams?.maxTokens,
            ...(shouldEnableThinking
              ? {
                thinkingConfig: {
                  includeThoughts: true,
                  thinkingLevel: options?.inferenceParams?.thinkingLevel?.toUpperCase(), // MINIMAL, LOW, MEDIUM, HIGH
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

        // Initialize tools array
        const tools: any[] = [];

        // 🆕 Phase 3 重构：始终为 Gemini 启用原生 Google Search
        // Gemini 模型自主判断何时需要联网搜索，无需手动开关
        // 原 search_internet 工具应已在 registry.getEnabledSkillsForModel 中被过滤
        tools.push({ googleSearch: {} });
        console.log('[Gemini] Native Google Search enabled (always-on for Gemini models)');

        // 🛡️ 二次防护：即便上游未过滤，这里也确保移除 search_internet
        // 避免与原生 Google Search 冲突
        const effectiveSkills = (options?.skills || []).filter((s: Skill) => s.id !== 'search_internet');

        // Add Skills (Function Declarations) if present
        if (effectiveSkills.length > 0) {
          const geminiTools = this.mapSkillsToGeminiTools(effectiveSkills);
          tools.push(...geminiTools);

          // Force tool usage if possible
          body.toolConfig = {
            functionCallingConfig: {
              mode: 'AUTO'
            }
          };
        }

        if (tools.length > 0) {
          body.tools = tools;
        }

        console.log('[GeminiClient] Request Body Tools:', JSON.stringify(body.tools, null, 2));


        let lastPosition = 0;
        let buffer = '';

        xhr.onreadystatechange = () => {
          console.log(`[GeminiClient] ReadyState: ${xhr.readyState}, Status: ${xhr.status}`);
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
                      // Handling array wrapper if present
                      const item = Array.isArray(json) ? json[0] : json;
                      console.log('[GeminiClient] Parsed JSON item:', JSON.stringify(item).substring(0, 500));
                      const candidates = item?.candidates;
                      const candidate = candidates?.[0];
                      let text = '';
                      let reasoning = '';

                      // Parse Usage Metadata
                      let usage: { input: number; output: number; total: number } | undefined;
                      const usageMetadata = item?.usageMetadata;
                      if (usageMetadata) {
                        usage = {
                          input: usageMetadata.promptTokenCount || 0,
                          output: usageMetadata.candidatesTokenCount || 0,
                          total: usageMetadata.totalTokenCount || 0,
                        };
                      }

                      let toolCalls: ToolCall[] | undefined;

                      if (candidate?.content?.parts) {
                        for (const part of candidate.content.parts) {
                          // Extract Function Calls
                          if (part.functionCall) {
                            if (!toolCalls) toolCalls = [];
                            toolCalls.push({
                              id: Math.random().toString(36).substring(7),
                              name: part.functionCall.name,
                              arguments: part.functionCall.args,
                            });
                          }

                          // 🧠 Precise Reasoning/Thought Extraction (Part-level)
                          // Relaxed check: Accept truthy 'thought' property to handle variations
                          const isThoughtPart =
                            !!part.thought || // Handles true, "true", or non-empty string
                            typeof part.thought === 'string' ||
                            !!part.reasoning_content;

                          if (isThoughtPart) {
                            if (typeof part.thought === 'string') reasoning += part.thought;
                            if (part.reasoning_content) reasoning += part.reasoning_content;
                            // If it's a thinking part and has text, capture it as reasoning
                            if (part.text) {
                              reasoning += part.text;
                            }
                          } else if (part.text) {
                            // Non-thinking part text is actual content
                            text += part.text;
                          }
                        }
                      }

                      // Handle citations from grounding metadata
                      let citations: { title: string; url: string; source?: string }[] | undefined;
                      const groundingMetadata =
                        candidate?.groundingMetadata || item.groundingMetadata;
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

                      console.log('[GeminiClient] Extracted - Text:', text.length, 'Reasoning:', reasoning.length, 'ToolCalls:', toolCalls?.length || 0);
                      if (text || reasoning || (citations && citations.length > 0) || usage || (toolCalls && toolCalls.length > 0)) {
                        onChunk({
                          content: text,
                          reasoning: reasoning || undefined,
                          citations: citations && citations.length > 0 ? citations : undefined,
                          toolCalls,
                          usage,
                        });
                      } else {
                        console.warn('[GeminiClient] No content/reasoning/tools to emit in this chunk');
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
          console.error('[GeminiClient] XHR network error');
          const err = new Error('Network request failed');
          onError(err);
          reject(err);
          this.activeXhr = null;
        };

        console.log('[GeminiClient] Sending request with body length:', JSON.stringify(body).length);
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
            temperature: this.temperature,
          },
        }),
      });

      const latency = Date.now() - start;

      if (!response.ok) {
        // Rule 8.4: Capture HTML error pages
        const contentType = response.headers.get('Content-Type') || '';
        if (!contentType.includes('application/json')) {
          const text = await response.text();
          if (text.trim().startsWith('<')) {
            return { success: false, latency, error: `Received HTML error page instead of JSON.` };
          }
          return {
            success: false,
            latency,
            error: `HTTP ${response.status}: ${text.substring(0, 100)}`,
          };
        }

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
  async generateImage(
    prompt: string,
    options?: { size?: string },
  ): Promise<{ url: string; revisedPrompt?: string }> {
    // Gemini API Studio protocol for Image Generation (Imagen)
    // Ref: https://ai.google.dev/gemini-api/docs/imagen

    const isGemini = this.model.startsWith('gemini-');
    const method = isGemini ? 'generateContent' : 'predict';
    const endpoint = `${this.baseUrl}/v1beta/models/${this.model}:${method}?key=${this.apiKey}`;

    let body: any;
    if (isGemini) {
      body = {
        contents: [{ role: 'user', parts: [{ text: prompt }] }],
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
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Gemini Image Error (${response.status}): ${errorText}`);
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
      throw new Error('Invalid response format or no image generated from Gemini API');
    }

    // Persist to file to avoid lag
    try {
      const { saveGeneratedImage } = require('../../image-utils');
      const { thumbnailUri } = await saveGeneratedImage(base64Data, mimeType);
      return { url: thumbnailUri };
    } catch (e) {
      console.warn('[Gemini] Failed to save image to file, falling back to data URI', e);
      return { url: `data:${mimeType};base64,${base64Data}` };
    }
  }
}
