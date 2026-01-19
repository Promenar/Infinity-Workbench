import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { ErrorNormalizer } from '../error-normalizer';
import { Skill, ToolCall } from '../../../types/skills';
import { apiLogger } from '../api-logger';
import zodToJsonSchema from 'zod-to-json-schema/dist/cjs/index.js';

export class OpenAiClient implements LlmClient {
  private apiKey: string;
  private baseUrl: string;
  private model: string;
  private temperature: number;
  private activeXhr: XMLHttpRequest | null = null;
  private isEmbedding: boolean = false;

  constructor(
    apiKey: string,
    model: string,
    temperature: number,
    baseUrl: string = 'https://api.openai.com/v1',
    options?: { isEmbedding?: boolean },
  ) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.model = model;
    this.temperature = temperature;
    this.isEmbedding = options?.isEmbedding ?? false;
  }

  async streamChat(
    messages: ChatMessage[],
    onToken: (token: string | any) => void,
    onError: (err: Error) => void,
    options?: any,
  ): Promise<void> {
    try {
      await this.fetchChatCompletion(messages, onToken, onError, options);
    } catch (error: any) {
      onError(error);
    }
  }

  async chatCompletion(
    messages: ChatMessage[],
    options?: any,
  ): Promise<{ content: string; toolCalls?: ToolCall[]; usage?: { input: number; output: number; total: number } }> {
    let result = '';
    let finalUsage: { input: number; output: number; total: number } | undefined;
    let finalToolCalls: ToolCall[] | undefined;

    await this.fetchChatCompletion(
      messages,
      (token) => {
        if (typeof token === 'string') result += token;
        else {
          if (token.content) result += token.content;
          if (token.usage) finalUsage = token.usage;
          if (token.toolCalls) finalToolCalls = token.toolCalls;
        }
      },
      (err) => {
        throw err;
      },
      { ...options, stream: false },
    );
    return { content: result, toolCalls: finalToolCalls, usage: finalUsage };
  }

  private mapSkillsToOpenAITools(skills: Skill[]): any[] {
    // Helper to enforce Strict Mode requirements (All objects must have additionalProperties: false)
    const enforceStrictSchema = (schema: any): any => {
      if (!schema || typeof schema !== 'object') return schema;

      const newSchema = { ...schema };

      if (newSchema.type === 'object') {
        newSchema.additionalProperties = false;

        // Ensure required fields are explicit
        if (!newSchema.required && newSchema.properties) {
          newSchema.required = Object.keys(newSchema.properties);
        }
      }

      if (newSchema.properties) {
        const newProps: any = {};
        for (const key in newSchema.properties) {
          newProps[key] = enforceStrictSchema(newSchema.properties[key]);
        }
        newSchema.properties = newProps;
      }

      if (newSchema.items) {
        newSchema.items = enforceStrictSchema(newSchema.items);
      }

      return newSchema;
    };

    return skills.map((skill) => {
      // 🧐 Force OpenAI/JSON Schema 7 compatibility
      let schema = zodToJsonSchema(skill.schema as any, {
        target: 'openApi3',
        $refStrategy: 'none'
      }) as any;

      // Deep clone and clean
      schema = JSON.parse(JSON.stringify(schema));
      delete schema.$schema;
      if (schema.definitions) delete schema.definitions;

      // Ensure 'type' is present for parameters
      if (!schema.type) {
        schema.type = 'object';
      }

      // Apply Strict Mode recursively
      schema = enforceStrictSchema(schema);

      return {
        type: 'function',
        function: {
          name: skill.id,
          description: skill.description,
          parameters: schema,
          strict: true, // 🔒 ENABLE STRICT MODE
        },
      };
    });
  }

  private async fetchChatCompletion(
    messages: ChatMessage[],
    onToken: (token: string | any) => void,
    onError: (err: Error) => void,
    options?: ChatMessageOptions & { stream?: boolean },
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.activeXhr = new XMLHttpRequest();
        const xhr = this.activeXhr;
        xhr.open('POST', `${this.baseUrl}/chat/completions`);
        xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.setRequestHeader('Authorization', `Bearer ${this.apiKey}`);

        let lastPosition = 0;
        const currentToolCalls: Record<number, { id: string; name: string; arguments: string }> = {};
        let isInsideThinkTag = false;

        // Helper to try parsing partial JSON
        const safeJsonParse = (str: string) => {
          try {
            return JSON.parse(str);
          } catch (e) {
            return {};
          }
        };

        xhr.onreadystatechange = () => {
          const stream = options?.stream ?? true; // Consistent with send

          if (stream) {
            // Streaming Logic (SSE)
            if (xhr.readyState === 3 || xhr.readyState === 4) {
              const newText = xhr.responseText.substring(lastPosition);
              lastPosition = xhr.responseText.length;

              const lines = newText.split('\n');
              for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed || !trimmed.startsWith('data: ')) continue;
                const data = trimmed.slice(6);
                if (data === '[DONE]') {
                  // 🔑 显式关闭XHR以防止并发连接残留（GLM等严格限额3的供应商需要）
                  if (xhr.readyState !== 4) {
                    xhr.abort();
                  }
                  resolve();
                  return;
                }
                try {
                  const json = JSON.parse(data);
                  const delta = json.choices?.[0]?.delta;
                  let content = delta?.content || '';
                  let reasoning = delta?.reasoning_content || '';
                  const usageRaw = json.usage;
                  const deltaToolCalls = delta?.tool_calls;

                  // 🧐 DeepSeek-Chat <think> tag support
                  if (content.includes('<think>')) {
                    isInsideThinkTag = true;
                    const parts = content.split('<think>');
                    // Everything before <think> stays as content
                    content = parts[0];
                    // Everything after <think> goes to reasoning
                    reasoning += parts.slice(1).join('<think>');
                  } else if (content.includes('</think>')) {
                    isInsideThinkTag = false;
                    const parts = content.split('</think>');
                    // Everything before </think> goes to reasoning
                    reasoning += parts[0];
                    // Everything after </think> stays as content
                    content = parts.slice(1).join('</think>');
                  } else if (isInsideThinkTag) {
                    // If we are deep inside the tag, move all content to reasoning
                    reasoning += content;
                    content = '';
                  }

                  // Debug Logic
                  if (content && content.length > 0) {
                    // console.log('[OpenAiClient] Received content chunk');
                  }

                  // Accumulate Tool Calls
                  if (deltaToolCalls) {
                    for (const tc of deltaToolCalls) {
                      const index = tc.index;
                      if (!currentToolCalls[index]) {
                        currentToolCalls[index] = {
                          id: tc.id || '',
                          name: tc.function?.name || '',
                          arguments: tc.function?.arguments || '',
                        };
                      } else {
                        // Append arguments
                        if (tc.function?.arguments) {
                          currentToolCalls[index].arguments += tc.function.arguments;
                        }
                        // It's possible for id/name to be split but usually they come in the first chunk
                        if (tc.id && !currentToolCalls[index].id) currentToolCalls[index].id = tc.id;
                        if (tc.function?.name && !currentToolCalls[index].name) currentToolCalls[index].name = tc.function.name;
                      }
                    }
                  }

                  let usage: { input: number; output: number; total: number } | undefined;
                  if (usageRaw) {
                    usage = {
                      input: usageRaw.prompt_tokens,
                      output: usageRaw.completion_tokens,
                      total: usageRaw.total_tokens,
                    };
                  }

                  // Convert accumulated tool calls to array for the callback
                  const toolCallsArray = Object.values(currentToolCalls).map(tc => ({
                    id: tc.id,
                    name: tc.name,
                    arguments: tc.arguments, // Keep as string for now, parse later
                  })).filter(tc => tc.id && tc.name); // Only emit if we have at least id and name (though args might need completion)

                  // Note: JSON.parse on streaming arguments is dangerous because they are incomplete.
                  // However, chat-store expects 'toolCalls' to be the FINAL structure in each update?
                  // Actually chat-store just assigns it.
                  // If we parse incomplete JSON, it will crash.
                  // We should only define toolCalls in onToken when we are "done" or if we want partials.
                  // But chat-store uses it essentially at the END of the stream loop (when toolCalls is checked).
                  // So we can just pass the raw Accumulated structure and let chat-store parse it?
                  // Or, better: we only pass toolCalls when we have them, but keep them as raw string in a hidden field?
                  // No, the interface says `toolCalls: ToolCall[]` where arguments is `any` (usually object).

                  // Better Strategy:
                  // Pass the toolCalls ONLY when they are valid or wait until stream end?
                  // Streaming tool calls updates is useful for UI (e.g. showing args being typed).
                  // But `safeJson` is needed.

                  let safeToolCalls: ToolCall[] | undefined;
                  if (toolCallsArray.length > 0) {
                    safeToolCalls = toolCallsArray.map(tc => {
                      const argsStr = tc.arguments.trim();
                      // 🛡️ 成熟度检查：如果 JSON 还没开始（只有 { ）或者明显不完整，
                      // 我们只返回 id 和 name，但在 arguments 中通过标记或空对象保留。
                      // 关键：不要让 chat-store 误以为这是一个已经“完成”的空指令。

                      let parsedArgs = {};
                      // 只有当字符串看起来像一个包含内容的 JSON 对象时才尝试解析
                      if (argsStr.length > 2 && argsStr.includes(':')) {
                        parsedArgs = safeJsonParse(argsStr);
                      }

                      return { ...tc, arguments: parsedArgs };
                    }).filter(tc => {
                      // 只有当参数包含实际内容，或者我们确定这不是一个误发时才发送
                      // DeepSeek 早期会发一个 id 但没 args，这会导致 chat-store 立即触发 tool 执行
                      const argsStr = tc.arguments ? JSON.stringify(tc.arguments) : '{}';
                      return argsStr !== '{}';
                    });
                  }

                  if (content || reasoning || usage || (safeToolCalls && safeToolCalls.length > 0)) {
                    onToken({
                      content,
                      reasoning,
                      usage,
                      toolCalls: (safeToolCalls && safeToolCalls.length > 0) ? safeToolCalls : undefined
                    });
                  }
                } catch (e) {
                  // Partial JSON, ignore or wait
                }
              }
            }
            if (xhr.readyState === 4) {
              if (xhr.status === 0) {
                resolve();
                return;
              }
              if (xhr.status >= 200 && xhr.status < 300) {
                // For streaming, the resolve is called when [DONE] is hit.
                // We could log chunks here but it might be overkill.
              } else {
                apiLogger.logResponse('openai', xhr.status, xhr.responseText);
                this.handleError(xhr, onError, reject);
              }
            }
          } else {
            // Non-Streaming Logic (Full JSON)
            if (xhr.readyState === 4) {
              if (xhr.status === 0) {
                resolve();
                return;
              }
              if (xhr.status >= 200 && xhr.status < 300) {
                try {
                  const json = JSON.parse(xhr.responseText);
                  const message = json.choices?.[0]?.message;
                  const usageRaw = json.usage;
                  let content = message?.content || '';
                  let reasoning = message?.reasoning_content || '';

                  // Handle <think> tags in non-streaming response
                  if (content.includes('<think>')) {
                    const match = content.match(/<think>([\s\S]*?)<\/think>/);
                    if (match) {
                      reasoning = match[1];
                      content = content.replace(/<think>[\s\S]*?<\/think>/, '').trim();
                    }
                  }

                  let toolCalls: ToolCall[] | undefined;
                  if (message?.tool_calls) {
                    toolCalls = message.tool_calls.map((tc: any) => ({
                      id: tc.id,
                      name: tc.function.name,
                      arguments: JSON.parse(tc.function.arguments),
                    }));
                  }

                  let usage: { input: number; output: number; total: number } | undefined;
                  if (usageRaw) {
                    usage = {
                      input: usageRaw.prompt_tokens,
                      output: usageRaw.completion_tokens,
                      total: usageRaw.total_tokens,
                    };
                  }

                  onToken({ content, toolCalls, usage, reasoning });
                  apiLogger.logResponse('openai', xhr.status, xhr.responseText);
                  resolve();
                } catch (e) {
                  onError(e as Error);
                  reject(e);
                }
              } else {
                this.handleError(xhr, onError, reject);
              }
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

        const stream = options?.stream ?? true;
        const skills = options?.skills;

        const payload: any = {
          model: this.model,
          messages: messages.map((m, idx) => {
            const msg: any = { role: m.role };

            // OpenAI treats 'content' as mandatory string/null. 
            // Handle array content (multimodal) only for 'user'
            if (m.role === 'user') {
              msg.content = m.content;
            } else {
              // For assistant/tool/system, content MUST be string or null
              msg.content = typeof m.content === 'string' ? m.content : null;
            }

            // 🧐 CRITICAL: DO NOT include reasoning_content in history for OpenAI-compatible providers.
            // Many (like DeepSeek) reject requests containing this field as it is output-only.
            // if (m.reasoning) msg.reasoning_content = m.reasoning; // ❌ Removed

            // 🧐 CRITICAL: Format tool_calls ONLY for assistant specification
            if (m.role === 'assistant' && m.tool_calls && m.tool_calls.length > 0) {
              msg.tool_calls = m.tool_calls.map((tc: any) => ({
                id: tc.id || (tc.function?.id) || `call_legacy_${idx}`, // Preserve or manufacture
                type: 'function',
                function: {
                  name: tc.name || tc.function?.name,
                  arguments: typeof tc.arguments === 'string'
                    ? tc.arguments
                    : JSON.stringify(tc.arguments || tc.function?.arguments)
                }
              }));
              // 🧐 Optimization: For assistant messages with tool_calls, some gateways (SiliconFlow/DeepSeek)
              // might reject content: null if they expect the preceding text to match.
              // We use empty string which is widely accepted.
              if (typeof msg.content === 'string' && msg.content.trim() === '') {
                msg.content = '';
              }
            }

            // 🧐 CRITICAL: Format tool_call_id ONLY for tool role
            if (m.role === 'tool') {
              msg.tool_call_id = m.tool_call_id || (m as any).id || (m as any).name;
              // Ensure we don't have an empty ID if possible
              if (!msg.tool_call_id) msg.tool_call_id = `call_legacy_${idx - 1}`; // Very dangerous fallback
            }

            if (m.name && m.role !== 'tool') msg.name = m.name;

            return msg;
          }),
          temperature: options?.inferenceParams?.temperature ?? this.temperature,
          top_p: options?.inferenceParams?.topP,
          max_tokens: options?.inferenceParams?.maxTokens,
          frequency_penalty: options?.inferenceParams?.frequencyPenalty,
          presence_penalty: options?.inferenceParams?.presencePenalty,
          tools: (skills && skills.length > 0) ? this.mapSkillsToOpenAITools(skills) : undefined,
          tool_choice: (skills && skills.length > 0) ? 'auto' : undefined,
          stream,
          stream_options: (stream && this.baseUrl.includes('openai.com')) ? { include_usage: true } : undefined,
        };

        if (skills && skills.length > 0) {
          // console.log('[OpenAiClient] Request Body Tools:', JSON.stringify(payload.tools, null, 2));
        }

        apiLogger.logRequest('openai', `${this.baseUrl}/chat/completions`, payload);

        xhr.send(JSON.stringify(payload));
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
      const isEmbeddingModel =
        this.isEmbedding ||
        this.model.toLowerCase().includes('embedding') ||
        this.model.toLowerCase().includes('bge') ||
        this.model.toLowerCase().includes('gte') ||
        this.model.toLowerCase().includes('vector');

      const endpoint = isEmbeddingModel
        ? `${this.baseUrl}/embeddings`
        : `${this.baseUrl}/chat/completions`;

      const body = isEmbeddingModel
        ? { model: this.model, input: 'ping' }
        : { model: this.model, messages: [{ role: 'user', content: 'ping' }], max_tokens: 1 };

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.apiKey}`,
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

  async testRerankConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
    const start = Date.now();
    try {
      // 处理baseUrl可能已包含/v1的情况
      const baseUrlClean = this.baseUrl.endsWith('/v1') ? this.baseUrl : `${this.baseUrl}/v1`;
      const endpoint = `${baseUrlClean}/rerank`;

      const body = {
        model: this.model,
        query: 'Apple',
        documents: ['apple', 'banana'],
        top_n: 2,
      };

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify(body),
      });

      const latency = Date.now() - start;

      if (!response.ok) {
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
    options?: { size?: string; style?: string; quality?: string },
  ): Promise<{ url: string; revisedPrompt?: string }> {
    const endpoint = `${this.baseUrl}/images/generations`;
    const body = {
      model: this.model,
      prompt,
      n: 1,
      size: options?.size || '1024x1024',
      quality: options?.quality,
      style: options?.style,
    };

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      // 🛡️ Rule 8.4: Capture HTML error pages
      const contentType = response.headers.get('Content-Type') || '';
      const errorText = await response.text();
      if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
        throw new Error(`HTTP ${response.status}: Received non-JSON response (possibly HTML error page).`);
      }
      throw new Error(`OpenAI Image Error (${response.status}): ${errorText}`);
    }

    const data = await response.json();
    if (data.data && data.data.length > 0) {
      return {
        url: data.data[0].url,
        revisedPrompt: data.data[0].revised_prompt,
      };
    }
    throw new Error('Invalid response format from OpenAI Image API');
  }

  async complete(
    options: {
      prompt: string;
      suffix?: string;
      maxTokens?: number;
      temperature?: number;
      stop?: string[];
    },
  ): Promise<{ content: string; usage?: { input: number; output: number; total: number } }> {
    const isDeepSeek = this.baseUrl.includes('deepseek');
    // DeepSeek FIM is currently in beta endpoint
    const endpoint = isDeepSeek
      ? `${this.baseUrl.replace(/\/v1$/, '')}/beta/completions`
      : `${this.baseUrl}/completions`;

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify({
        model: this.model,
        prompt: options.prompt,
        suffix: options.suffix,
        max_tokens: options.maxTokens || 1024,
        temperature: options.temperature ?? 0,
        stop: options.stop,
      }),
    });

    if (!response.ok) {
      // 🛡️ Rule 8.4: Capture HTML error pages
      const contentType = response.headers.get('Content-Type') || '';
      const errorText = await response.text();
      if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
        throw new Error(`HTTP ${response.status}: Received non-JSON response (possibly HTML error page).`);
      }
      throw new Error(`Completion Error (${response.status}): ${errorText}`);
    }

    const data = await response.json();
    return {
      content: data.choices?.[0]?.text || '',
      usage: data.usage
        ? {
          input: data.usage.prompt_tokens,
          output: data.usage.completion_tokens,
          total: data.usage.total_tokens,
        }
        : undefined,
    };
  }

  /**
   * 🛠️ Rule 8.4 Compliant Embedding Method
   */
  async embeddings(input: string | string[]): Promise<{ embeddings: number[][]; usage: { total_tokens: number } }> {
    // 启发式路径修正：如果 URL 看起来像聚合器且缺少 /v1，自动尝试补全
    const isAggregator = this.baseUrl.includes('api.') || this.baseUrl.includes('api-');
    const hasV1 = this.baseUrl.endsWith('/v1') || this.baseUrl.includes('/v1/');
    const baseUrlInternal = (isAggregator && !hasV1) ? `${this.baseUrl.replace(/\/$/, '')}/v1` : this.baseUrl;

    const endpoint = `${baseUrlInternal}/embeddings`;
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify({
        model: this.model,
        input: Array.isArray(input) ? input : [input],
      }),
    });

    if (!response.ok) {
      // 🛡️ Rule 8.4: Capture HTML error pages
      const contentType = response.headers.get('Content-Type') || '';
      const errorText = await response.text();
      if (errorText.trim().startsWith('<') || !contentType.includes('application/json')) {
        throw new Error(`HTTP ${response.status}: Received non-JSON response from ${endpoint} (possibly HTML error page).`);
      }
      throw new Error(`Embedding Error (${response.status}): ${errorText}`);
    }

    const data = await response.json();
    if (!data.data || !Array.isArray(data.data)) {
      throw new Error('Invalid OpenAI embedding response: missing data array');
    }

    const embeddings = data.data
      .sort((a: any, b: any) => (a.index || 0) - (b.index || 0))
      .map((item: any) => item.embedding);

    return {
      embeddings,
      usage: {
        total_tokens: data.usage?.total_tokens || 0,
      },
    };
  }

  private handleError(xhr: XMLHttpRequest, onError: (err: Error) => void, reject: (reason?: any) => void) {
    const rawError = {
      status: xhr.status,
      statusText: xhr.statusText,
      message: `API Error: ${xhr.status} ${xhr.statusText}\n${xhr.responseText}`,
      response: xhr.responseText,
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
