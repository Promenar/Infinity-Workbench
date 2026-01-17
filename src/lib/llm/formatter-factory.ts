import { ProviderType } from './response-normalizer';
import { MessageFormatter } from './message-formatter';
import {
    OpenAIFormatter,
    DeepSeekFormatter,
    GLMFormatter,
    MoonshotFormatter,
    GeminiFormatter,
} from './formatters/provider-formatters';

/**
 * FormatterFactory
 * 
 * 根据Provider类型创建对应的MessageFormatter
 */
export class FormatterFactory {
    private static formatters: Map<string, MessageFormatter> = new Map();

    /**
     * 获取Provider对应的Formatter（缓存策略）
     * @param provider Provider类型
     * @param modelName 模型名称（可选，用于模型特定优化）
     */
    static getFormatter(provider: ProviderType, modelName?: string): MessageFormatter {
        // 🔑 为支持modelName，使用组合键缓存
        const cacheKey = modelName ? `${provider}:${modelName}` : provider;

        if (!this.formatters.has(cacheKey)) {
            this.formatters.set(cacheKey, this.createFormatter(provider, modelName));
        }
        return this.formatters.get(cacheKey)!;
    }

    private static createFormatter(provider: ProviderType, modelName?: string): MessageFormatter {
        switch (provider) {
            case 'local':
            case 'openai':
            case 'siliconflow':
            case 'github':
                return new OpenAIFormatter();

            case 'deepseek':
                return new DeepSeekFormatter(modelName);  // 🔑 传递modelName

            case 'zhipu':
                return new GLMFormatter();

            case 'moonshot':
                return new MoonshotFormatter();

            case 'gemini':
            case 'vertex':
            case 'google':  // VertexAI uses 'google' as provider type
                return new GeminiFormatter(modelName);  // 🔑 传递modelName

            default:
                // 降级：使用OpenAI格式
                console.warn(`[FormatterFactory] Unknown provider: ${provider}, using OpenAI formatter`);
                return new OpenAIFormatter();
        }
    }

    /**
     * 清除缓存（用于测试）
     */
    static clearCache() {
        this.formatters.clear();
    }
}
