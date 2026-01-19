import { LlmClient, ChatMessage, ChatMessageOptions } from '../types';
import { ErrorNormalizer } from '../error-normalizer';
import { Skill, ToolCall } from '../../../types/skills';
import { apiLogger } from '../api-logger';
import zodToJsonSchema from 'zod-to-json-schema/dist/cjs/index.js';

/**
 * MoonshotClient (Kimi)
 * 为 Moonshot API 提供专用支持。
 * 
 * 核心特性：
 * 1. 禁用 Strict Mode：Kimi 目前对 OpenAI 的 strict: true 支持不佳。
 * 2. 保留 reasoning_content：Kimi k2 thinking 模型要求在历史记录中包含之前的推理内容。
 * 3. 增强的工具解析：支持 kimi 特定的标签。
 */
export class MoonshotClient implements LlmClient {
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
        baseUrl: string = 'https://api.moonshot.cn/v1',
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

            return {
                type: 'function',
                function: {
                    name: skill.id,
                    description: skill.description,
                    parameters: schema,
                    strict: false, // 🔓 MOONSHOT: Disable strict mode to avoid 400 errors
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
                            lastPosition = xhr.responseText.length;

                            const lines = newText.split('\n');
                            for (const line of lines) {
                                const trimmed = line.trim();
                                if (!trimmed || !trimmed.startsWith('data: ')) continue;
                                const data = trimmed.slice(6);
                                if (data === '[DONE]') {
                                    if (xhr.readyState !== 4) xhr.abort();
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

                                    // Tool Call Accumulation
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
                                                if (tc.function?.arguments) currentToolCalls[index].arguments += tc.function.arguments;
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
                                        }).filter(tc => {
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
                                } catch (e) { }
                            }
                        }
                        if (xhr.readyState === 4) {
                            if (xhr.status === 0) {
                                resolve();
                                return;
                            }
                            if (xhr.status < 200 || xhr.status >= 300) {
                                apiLogger.logResponse('moonshot', xhr.status, xhr.responseText);
                                this.handleError(xhr, onError, reject);
                            }
                        }
                    } else {
                        // Non-Streaming
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
                                    apiLogger.logResponse('moonshot', xhr.status, xhr.responseText);
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

                        // 🔑 MOONSHOT: Preserve reasoning_content for k2-thinking context
                        if (m.role === 'assistant' && m.reasoning !== undefined) {
                            msg.reasoning_content = m.reasoning;
                        }

                        if (m.role === 'assistant' && m.tool_calls && m.tool_calls.length > 0) {
                            msg.tool_calls = m.tool_calls.map((tc: any) => ({
                                id: tc.id || `call_legacy_${idx}`,
                                type: 'function',
                                function: {
                                    name: tc.name,
                                    arguments: typeof tc.arguments === 'string' ? tc.arguments : JSON.stringify(tc.arguments)
                                }
                            }));
                            if (typeof msg.content === 'string' && msg.content.trim() === '') {
                                msg.content = '';
                            }
                        }

                        if (m.role === 'tool') {
                            msg.tool_call_id = m.tool_call_id;
                            msg.name = m.name;
                        }

                        return msg;
                    }),
                    temperature: options?.inferenceParams?.temperature ?? this.temperature,
                    top_p: options?.inferenceParams?.topP,
                    max_tokens: options?.inferenceParams?.maxTokens,
                    tools: (skills && skills.length > 0) ? this.mapSkillsToOpenAITools(skills) : undefined,
                    tool_choice: (skills && skills.length > 0) ? 'auto' : undefined,
                    stream,
                };

                apiLogger.logRequest('moonshot', `${this.baseUrl}/chat/completions`, payload);
                xhr.send(JSON.stringify(payload));
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
            const response = await fetch(`${this.baseUrl}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${this.apiKey}`,
                },
                body: JSON.stringify({ model: this.model, messages: [{ role: 'user', content: 'ping' }], max_tokens: 1 }),
            });

            const latency = Date.now() - start;
            if (!response.ok) {
                const errorText = await response.text();
                return { success: false, latency, error: `HTTP ${response.status}: ${errorText.substring(0, 100)}` };
            }
            return { success: true, latency };
        } catch (e) {
            return { success: false, latency: Date.now() - start, error: (e as Error).message };
        }
    }

    private handleError(xhr: XMLHttpRequest, onError: (err: Error) => void, reject: (reason?: any) => void) {
        const rawError = {
            status: xhr.status,
            statusText: xhr.statusText,
            message: `Moonshot API Error: ${xhr.status} ${xhr.statusText}\n${xhr.responseText}`,
            response: xhr.responseText,
        };
        const normalized = ErrorNormalizer.normalize(rawError, 'openai');
        const err = new Error(normalized.message);
        onError(err);
        reject(err);
    }
}
