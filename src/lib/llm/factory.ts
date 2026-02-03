import { LlmClient } from './types';
import { OpenAiClient } from './providers/openai';
import { DeepSeekClient } from './providers/deepseek'; // 🔑 DeepSeek专用Client
import { MoonshotClient } from './providers/moonshot'; // 🔑 Moonshot专用Client
import { OpenAiCompatibleClient } from './providers/openai-compatible'; // 🔑 通用兼容Client
import { VertexAiClient } from './providers/vertexai';
import { GeminiClient } from './providers/gemini';
import { LocalLlmClient } from './providers/local-llm';
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
    case 'zhipu':
    case 'siliconflow':
    case 'github':
    case 'cloudflare':
    case 'github-copilot':
      return new OpenAiClient(
        config.apiKey,
        config.id, // 使用模型 API ID
        config.temperature || 0.7,
        config.baseUrl || 'https://api.openai.com/v1',
        { isEmbedding: config.type === 'embedding' },
      );

    case 'openai-compatible':
      return new OpenAiCompatibleClient(
        config.apiKey,
        config.id,
        config.temperature || 0.7,
        config.baseUrl || 'https://api.openai.com/v1',
        { isEmbedding: config.type === 'embedding' },
      );

    case 'moonshot':
      return new MoonshotClient(
        config.apiKey,
        config.id,
        config.temperature || 0.7,
        config.baseUrl || 'https://api.moonshot.cn/v1',
        { isEmbedding: config.type === 'embedding' },
      );

    case 'local':
      return new LocalLlmClient(
        config.id,
        config.temperature || 0.7
      );

    // 🔑 DeepSeek专用Provider
    case 'deepseek':
      return new DeepSeekClient(
        config.apiKey,
        config.id,
        config.temperature || 0.7,
        config.baseUrl || 'https://api.deepseek.com/v1',
        { isEmbedding: config.type === 'embedding' },
      );

    case 'gemini':
      return new GeminiClient(
        config.apiKey,
        config.id,
        config.temperature || 0.7,
        config.baseUrl || 'https://generativelanguage.googleapis.com',
      );
    case 'google':
      return new VertexAiClient({
        apiKey: config.apiKey,
        model: config.id,
        temperature: config.temperature || 0.7,
        baseUrl: config.baseUrl || '',
        project: config.vertexProject,
        location: config.vertexLocation,
        keyJson: config.vertexKeyJson,
      });
    default:
      throw new Error(`Provider ${config.provider} not supported`);
  }
};
