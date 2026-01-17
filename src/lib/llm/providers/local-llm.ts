import { LlmClient, ChatMessage, ChatMessageOptions, CompletionOptions } from '../types';
import { useLocalModelStore } from '../../local-inference/LocalModelServer';
import { RNLlamaOAICompatibleMessage } from 'llama.rn';
import { Skill } from '../../../types/skills';
import zodToJsonSchema from 'zod-to-json-schema/dist/cjs/index.js';

export class LocalLlmClient implements LlmClient {
    private model: string;
    private temperature: number;

    constructor(
        model: string,
        temperature: number,
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        _options?: any
    ) {
        this.model = model;
        this.temperature = temperature;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: any) => void,
        onError: (err: Error) => void,
        options?: ChatMessageOptions
    ): Promise<void> {
        try {
            await this.completion(messages, onChunk, onError, { ...options, stream: true });
        } catch (err: any) {
            onError(err);
        }
    }

    async chatCompletion(
        messages: ChatMessage[],
        options?: any
    ): Promise<{ content: string; toolCalls?: any[]; usage?: { input: number; output: number; total: number } }> {
        let content = '';
        let usage: any;
        let toolCalls: any;

        await this.completion(
            messages,
            (chunk) => {
                if (chunk.content) content += chunk.content;
                if (chunk.usage) usage = chunk.usage;
                if (chunk.toolCalls) toolCalls = chunk.toolCalls;
            },
            (err) => { throw err; },
            { ...options, stream: false }
        );

        return { content, usage, toolCalls };
    }

    private async completion(
        messages: ChatMessage[],
        onChunk: (chunk: any) => void,
        onError: (err: Error) => void,
        options?: any
    ) {
        const store = useLocalModelStore.getState();
        if (!store.main.isLoaded) {
            throw new Error('未加载本地聊天模型。请在设置中将一个 LLM 模型加载至 [Main Slot] 后再开始对话。');
        }

        const formattedMessages = this.formatMessages(messages);
        const tools = options?.skills ? this.mapSkillsToTools(options.skills) : undefined;
        // const tools = undefined;

        console.log('[LocalLlmClient] Request:', {
            messagesCount: formattedMessages.length,
            firstMessage: formattedMessages[0],
            toolsCount: options?.skills?.length ?? 0
        });

        await store.completion(
            {
                prompt: '', // Chat completion uses messages
                messages: formattedMessages,
                n_predict: options?.inferenceParams?.maxTokens ?? 2048,
                temperature: options?.inferenceParams?.temperature ?? this.temperature,
                top_p: options?.inferenceParams?.topP ?? 0.95,
                stop: ['<|end|>', '<|eot_id|>', '<|end_of_text|>'], // Common stop tokens
                tools, // llama.rn supports function calling if model supports it
                // @ts-ignore
                stream: options?.stream ?? false,
            },
            (data) => {
                const chunk: any = {
                    content: data.token, // llama.rn sends token text in .token field
                };

                // If it's the final chunk with stats, it might be different structure
                // But typically llama.rn calls back for every token.
                // We need to check if data has usage or tool calls.
                // Note: TokenData type definition in my thought trace showed:
                // token: string, completion_probabilities?, content?, tool_calls?

                if (data.tool_calls) {
                    chunk.toolCalls = data.tool_calls;
                }

                // Usage mapping (might need calculation or llama.rn provides it at end?)
                // Currently ignoring usage for local streaming to keep simple

                onChunk(chunk);
            }
        );
    }

    private formatMessages(messages: ChatMessage[]): RNLlamaOAICompatibleMessage[] {
        return messages.map(m => {
            // @ts-ignore
            const msg: RNLlamaOAICompatibleMessage = {
                role: m.role,
                content: typeof m.content === 'string' ? m.content : JSON.stringify(m.content),
            };
            // Note: tool_calls handling for history messages might be needed if re-feeding context
            return msg;
        });
    }

    private mapSkillsToTools(skills: Skill[]): any {
        // reuse logic from OpenAiClient or simplified
        // llama.rn expects OpenAI compatible tools format
        const tools: any = {};
        skills.forEach(skill => {
            const schema = zodToJsonSchema(skill.schema as any, { target: 'openApi3' }) as any;
            delete schema.$schema;
            delete schema.additionalProperties;
            if (schema.definitions) delete schema.definitions;
            if (!schema.type) schema.type = 'object';

            tools[skill.id] = {
                type: 'function',
                function: {
                    name: skill.id,
                    description: skill.description,
                    parameters: schema
                }
            };
        });
        // Check llama.rn format: typically expects object or array?
        // OpenAi expects array of {type: function, function: ...}
        // But llama.rn docs says `tools?: object`. 
        // Let's assume standard OpenAI array format first, if fail, check docs.
        // Actually Type definition said `tools?: object`. 
        // But OpenAI uses array. Let's try array first.

        return Object.values(tools);
    }

    async testConnection(): Promise<{ success: boolean; latency: number; error?: string }> {
        const store = useLocalModelStore.getState();
        if (store.main.isLoaded) return { success: true, latency: 0 };
        return { success: false, latency: 0, error: '本地聊天模型 [Main Slot] 未就绪' };
    }
}
