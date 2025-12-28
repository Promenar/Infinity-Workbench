import { LlmClient } from './types';
import { OpenAiClient } from './providers/openai';
import { VertexAiClient } from './providers/vertexai';
import { GeminiClient } from './providers/gemini';
import { ModelConfig } from '../../store/api-store';

// 扩展 ModelConfig 以便在工厂中使用带有 Provider 信息的配置
export interface ExtendedModelConfig extends ModelConfig {
    provider: string;
    apiKey: string;
    baseUrl?: string;
    temperature?: number;
    // VertexAI 特定
    vertexProject?: string;
    vertexLocation?: string;
    vertexKeyJson?: string;
}

export const createLlmClient = (config: ExtendedModelConfig): LlmClient => {
    switch (config.provider) {
        case 'openai':
        case 'deepseek':
        case 'moonshot':
        case 'zhipu':
        case 'siliconflow':
        case 'github':
        case 'cloudflare':
        case 'github-copilot':
        case 'local':
            return new OpenAiClient(
                config.apiKey,
                config.id, // 使用模型 API ID
                config.temperature || 0.7,
                config.baseUrl || 'https://api.openai.com/v1',
                { isEmbedding: config.type === 'embedding' }
            );
        case 'gemini':
            return new GeminiClient(
                config.apiKey,
                config.id,
                config.temperature || 0.7,
                config.baseUrl || 'https://generativelanguage.googleapis.com'
            );
        case 'google':
            return new VertexAiClient({
                apiKey: config.apiKey,
                model: config.id,
                temperature: config.temperature || 0.7,
                baseUrl: config.baseUrl || '',
                project: config.vertexProject,
                location: config.vertexLocation,
                keyJson: config.vertexKeyJson
            });
        default:
            throw new Error(`Provider ${config.provider} not supported`);
    }
};
