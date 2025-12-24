import { LlmClient } from './types';
import { OpenAiClient } from './providers/openai';
import { ModelConfig } from '../../store/api-store';

export const createLlmClient = (config: ModelConfig): LlmClient => {
    switch (config.provider) {
        case 'openai':
        case 'deepseek': // DeepSeek usually compatible with OpenAI SDK
            return new OpenAiClient(
                config.apiKey,
                config.modelName,
                config.temperature,
                config.baseUrl || 'https://api.openai.com/v1'
            );
        // case 'anthropic': return new AnthropicClient(...);
        default:
            throw new Error(`Provider ${config.provider} not supported`);
    }
};
