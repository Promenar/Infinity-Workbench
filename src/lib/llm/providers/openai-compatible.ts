import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { ErrorNormalizer } from '../error-normalizer';
import { Skill, ToolCall } from '../../../types/skills';
import { apiLogger } from '../api-logger';
import zodToJsonSchema from 'zod-to-json-schema/dist/cjs/index.js';

export class OpenAiCompatibleClient implements LlmClient {
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
        baseUrl: string,
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
        let finalReasoning = '';

        await this.fetchChatCompletion(
            messages,
            (token) => {
                if (typeof token === 'string') result += token;
                else {
                    if (token.content) result += token.content;
                    if (token.reasoning) finalReasoning += token.reasoning;
                    if (token.usage) finalUsage = token.usage;
                    if (token.toolCalls) finalToolCalls = token.toolCalls;
                }
            },
            (err) => {
                throw err;
            },
            { ...options, stream: false },
        );
        // 如果有reasoning但没有返回到content中（非标准情况），这里不合并，只要content。
        // 注意：chat-store可能会处理reasoning字段。
        return { content: result, toolCalls: finalToolCalls, usage: finalUsage };
    }

    private mapSkillsToOpenAITools(skills: Skill[]): any[] {
        const enforceStrictSchema = (schema: any): any => {
            if (!schema || typeof schema !== 'object') return schema;
            const newSchema = { ...schema };

            if (newSchema.type === 'object') {
                newSchema.additionalProperties = false;
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
            let schema = zodToJsonSchema(skill.schema as any, {
                target: 'openApi3',
                $refStrategy: 'none'
            }) as any;

            schema = JSON.parse(JSON.stringify(schema));
            delete schema.$schema;
            if (schema.definitions) delete schema.definitions;

            if (!schema.type) {
                schema.type = 'object';
            }

            // 默认不对通用兼容接口开启 Strict Mode，因为很多下游实现（如 OneAPI, NewAPI）可能不支持
            // 或者支持不完善导致 400 错误。
            // 如果将来需要，可以作为可配置项。

            return {
                type: 'function',
                function: {
                    name: skill.id,
                    description: skill.description,
                    parameters: schema,
                    strict: false, // 🔓 Disable strict mode for compatibility
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

                // 修复 URL 构建逻辑：
                // 很多用户填写的 Base URL 可能是 http://host/v1 或 http://host。
                // 标准做法是：确保 Base URL 不以 / 结尾，然后追加 /chat/completions
                const cleanBaseUrl = this.baseUrl.replace(/\/+$/, '');
                const url = `${cleanBaseUrl}/chat/completions`;

                xhr.open('POST', url);
                xhr.setRequestHeader('Content-Type', 'application/json');
                xhr.setRequestHeader('Authorization', `Bearer ${this.apiKey}`);

                // 允许用户配置额外的 Headers (暂略，如果需要可通过options传入)

                let lastPosition = 0;
                const currentToolCalls: Record<number, { id: string; name: string; arguments: string }> = {};
                let isInsideThinkTag = false;

                const safeJsonParse = (str: string) => {
                    try {
                        return JSON.parse(str);
                    } catch (e) {
                        return {};
                    }
                };

                xhr.onreadystatechange = () => {
                    const stream = options?.stream ?? true;

                    if (stream) {
                        if (xhr.readyState === 3 || xhr.readyState === 4) {
                            const newText = xhr.responseText.substring(lastPosition);

                            // 🚨 HTML Response (Proxy/Gateway Error) Detection
                            // If response starts with < (less than), it's likely an HTML error page (404/502 etc)
                            // Even if status is 200 (some generic error pages do this)
                            if (newText.trim().startsWith('<')) {
                                const errorMsg = `Received HTML response instead of JSON stream. Please check your Base URL settings.\n\nTip: NewAPI/OneAPI often requires appending '/v1' to the URL (e.g. 'https://api.example.com/v1').\n\nPrelude: ${newText.substring(0, 50)}...`;
                                xhr.abort();
                                onError(new Error(errorMsg));
                                return;
                            }

                            lastPosition = xhr.responseText.length;

                            const lines = newText.split('\n');
                            for (const line of lines) {
                                const trimmed = line.trim();
                                // 兼容有些API可能返回不带 data: 前缀的 JSON (虽然很少见但作为robustness考虑)
                                if (!trimmed) continue;

                                let data = '';
                                if (trimmed.startsWith('data: ')) {
                                    data = trimmed.slice(6);
                                } else {
                                    // 尝试直接解析行，如果不是json则忽略（可能是keep-alive或其他噪声）
                                    // 很多 SSE 实现只有 data: 开头
                                    if (trimmed === 'data: [DONE]') data = '[DONE]';
                                    else continue;
                                }

                                if (data === '[DONE]') {
                                    if (xhr.readyState !== 4) xhr.abort();
                                    resolve();
                                    return;
                                }

                                try {
                                    const json = JSON.parse(data);
                                    const choice = json.choices?.[0];
                                    const delta = choice?.delta;
                                    let content = delta?.content || '';
                                    let reasoning = delta?.reasoning_content || '';
                                    const usageRaw = json.usage; // NewAPI/OneAPI 可能在最后一个 chunk 返回 usage
                                    const deltaToolCalls = delta?.tool_calls;

                                    // 🛡️ <think> Tag Logic (Generic)
                                    // 即使是 OpenAI 兼容接口，后端可能是 DeepSeek 或其他支持思考的模型
                                    let contentToProcess = content;

                                    if (contentToProcess.includes('<think>')) {
                                        isInsideThinkTag = true;
                                        const parts = contentToProcess.split('<think>');
                                        content = parts[0];
                                        reasoning += parts.slice(1).join('<think>');
                                    } else if (contentToProcess.includes('</think>')) {
                                        isInsideThinkTag = false;
                                        const parts = contentToProcess.split('</think>');
                                        reasoning += parts[0];
                                        content = parts.slice(1).join('</think>');
                                    } else if (isInsideThinkTag) {
                                        reasoning += contentToProcess;
                                        content = '';
                                    }

                                    // 🛡️ Split Tag Safety 
                                    if (isInsideThinkTag && /^(think>|hink>|ink>|nk>|k>|>)/.test(contentToProcess)) {
                                        const match = /^(think>|hink>|ink>|nk>|k>|>)([\s\S]*)/.exec(contentToProcess);
                                        if (match) {
                                            isInsideThinkTag = false;
                                            reasoning += '';
                                            content = match[2];
                                        }
                                    }

                                    // 🛡️ Cleaning DeepSeek Special Tokens
                                    const cleanTokens = (text: string) => {
                                        if (!text) return text;
                                        return text
                                            .replace(/<\|end_of_thinking\|>/g, '')
                                            .replace(/< \| end__of__thinking \| >/g, '')
                                            .replace(/<\|endofthinking\|>/g, '')
                                            .trim();
                                    };
                                    content = cleanTokens(content);
                                    reasoning = cleanTokens(reasoning);

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
                                                if (tc.function?.arguments) {
                                                    currentToolCalls[index].arguments += tc.function.arguments;
                                                }
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

                                    const toolCallsArray = Object.values(currentToolCalls).map(tc => ({
                                        id: tc.id,
                                        name: tc.name,
                                        arguments: tc.arguments,
                                    })).filter(tc => tc.id && tc.name);

                                    let safeToolCalls: ToolCall[] | undefined;
                                    if (toolCallsArray.length > 0) {
                                        safeToolCalls = toolCallsArray.map(tc => {
                                            const argsStr = tc.arguments.trim();
                                            let parsedArgs = {};
                                            if (argsStr.length > 2 && argsStr.includes(':')) {
                                                parsedArgs = safeJsonParse(argsStr);
                                            }
                                            return { ...tc, arguments: parsedArgs };
                                        }).filter(tc => !!(tc.id && tc.name));
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
                                    // Ignore parse errors for partial chunks
                                }
                            }
                        }
                        if (xhr.readyState === 4) {
                            if (xhr.status === 0) {
                                // Aborted or network error handled in onerror
                                return;
                            }
                            if (xhr.status >= 200 && xhr.status < 300) {
                                // Finished
                                // 如果没有显式的 [DONE]，这里也会结束
                                resolve();
                            } else {
                                apiLogger.logResponse('openai-compatible', xhr.status, xhr.responseText);
                                this.handleError(xhr, onError, reject);
                            }
                        }
                    } else {
                        // Non-Streaming
                        if (xhr.readyState === 4) {
                            if (xhr.status >= 200 && xhr.status < 300) {
                                try {
                                    const json = JSON.parse(xhr.responseText);
                                    const message = json.choices?.[0]?.message;
                                    const usageRaw = json.usage;
                                    let content = message?.content || '';
                                    let reasoning = message?.reasoning_content || '';

                                    if (content.includes('<think>')) {
                                        const match = content.match(/<think>([\s\S]*?)<\/think>/);
                                        if (match) {
                                            reasoning = match[1];
                                            content = content.replace(/<think>[\s\S]*?<\/think>/, '').trim();
                                        }
                                    }

                                    // Clean tokens
                                    const cleanTokens = (text: string) => {
                                        if (!text) return text;
                                        return text.replace(/<\|end_of_thinking\|>/g, '').trim();
                                    };
                                    content = cleanTokens(content);
                                    reasoning = cleanTokens(reasoning);

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
                                    apiLogger.logResponse('openai-compatible', xhr.status, xhr.responseText);
                                    resolve();
                                } catch (e) {
                                    onError(e as Error);
                                    reject(e);
                                }
                            } else {
                                apiLogger.logResponse('openai-compatible', xhr.status, xhr.responseText);
                                this.handleError(xhr, onError, reject);
                            }
                        }
                    }
                };

                xhr.onerror = () => {
                    const rawError = new Error(`Network request failed to ${url}`);
                    const normalized = ErrorNormalizer.normalize(rawError, 'openai'); // Use 'openai' for generic network errors
                    const err = new Error(normalized.message);

                    apiLogger.logResponse('openai-compatible', 0, `Network Error: ${rawError.message}`);

                    onError(err);
                    reject(err);
                };

                const stream = options?.stream ?? true;
                const skills = options?.skills;

                const payload: any = {
                    model: this.model,
                    messages: messages.map((m, idx) => {
                        const msg: any = { role: m.role };

                        if (m.role === 'user') {
                            msg.content = m.content;
                        } else {
                            msg.content = typeof m.content === 'string' ? m.content : null;
                        }

                        // Generic Compatible Client: Strategy for reasoning_content
                        // 默认不发送 reasoning_content，除非明确知道后端支持。
                        // 大多数兼容 API（如 OneAPI）会过滤掉未知的字段，但有些严苛的校验可能会报错。
                        // 安全起见，只发送标准字段。

                        if (m.role === 'assistant' && m.tool_calls && m.tool_calls.length > 0) {
                            msg.tool_calls = m.tool_calls.map((tc: any) => ({
                                id: tc.id || (tc.function?.id) || `call_generic_${idx}`,
                                type: 'function',
                                function: {
                                    name: tc.name || tc.function?.name,
                                    arguments: typeof tc.arguments === 'string'
                                        ? tc.arguments
                                        : JSON.stringify(tc.arguments || tc.function?.arguments)
                                }
                            }));
                            if (typeof msg.content === 'string' && msg.content.trim() === '') {
                                msg.content = ''; // Ensure content is not null if string expected
                            }
                        }

                        // Tool Role
                        if (m.role === 'tool') {
                            msg.tool_call_id = m.tool_call_id;
                            msg.name = m.name;
                            // Ensure ID
                            if (!msg.tool_call_id) msg.tool_call_id = `call_generic_${idx - 1}`;
                        }
                        // Tool name in non-tool roles
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
                    // 尝试开启 usage reporting, 很多现代兼容 API 支持
                    stream_options: stream ? { include_usage: true } : undefined,
                };

                // 🛡️ Payload Hardening: Deep sanitization for maximum compatibility
                const cleanPayload: any = {
                    model: payload.model,
                    messages: payload.messages.map((m: any) => ({
                        role: m.role,
                        content: m.content ?? '', // Never send null content
                        ...(m.name ? { name: m.name } : {}),
                        ...(m.tool_calls ? { tool_calls: m.tool_calls } : {}),
                        ...(m.tool_call_id ? { tool_call_id: m.tool_call_id } : {}),
                    })),
                    stream: payload.stream,
                };

                // Add optional parameters ONLY if they are defined and non-default
                if (payload.temperature !== undefined) cleanPayload.temperature = payload.temperature;
                if (payload.top_p !== undefined && payload.top_p < 1) cleanPayload.top_p = payload.top_p;
                if (payload.max_tokens !== undefined) cleanPayload.max_tokens = payload.max_tokens;
                if (payload.presence_penalty !== undefined && payload.presence_penalty !== 0) cleanPayload.presence_penalty = payload.presence_penalty;
                if (payload.frequency_penalty !== undefined && payload.frequency_penalty !== 0) cleanPayload.frequency_penalty = payload.frequency_penalty;

                if (payload.stream_options && payload.stream) cleanPayload.stream_options = payload.stream_options;
                if (payload.tools && payload.tools.length > 0) {
                    cleanPayload.tools = payload.tools;
                    cleanPayload.tool_choice = payload.tool_choice || 'auto';
                }

                // 🚨 AGGRESSIVE DEBUGGING removed
                apiLogger.logRequest('openai-compatible', url, cleanPayload);

                xhr.send(JSON.stringify(cleanPayload));

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
            const url = `${this.baseUrl}/chat/completions`.replace(/([^:]\/)\/+/g, '$1');
            const body = {
                model: this.model,
                messages: [{ role: 'user', content: 'ping' }],
                max_tokens: 1
            };

            const response = await fetch(url, {
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

    private handleError(xhr: XMLHttpRequest, onError: (err: Error) => void, reject: (reason?: any) => void) {
        const rawError = {
            status: xhr.status,
            statusText: xhr.statusText,
            message: `API Error: ${xhr.status} ${xhr.statusText}\n${xhr.responseText}`,
            response: xhr.responseText,
        };

        // 🚨 AGGRESSIVE DEBUGGING: Log raw response
        console.error('[OpenAiCompatibleClient] RAW ERROR RESPONSE:', {
            status: xhr.status,
            response: xhr.responseText
        });

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
