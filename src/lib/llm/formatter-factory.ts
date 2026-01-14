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
    private static formatters: Map<ProviderType, MessageFormatter> = new Map();

    /**
     * 获取Provider对应的Formatter（单例模式）
     */
    static getFormatter(provider: ProviderType): MessageFormatter {
        if (!this.formatters.has(provider)) {
            this.formatters.set(provider, this.createFormatter(provider));
        }
        return this.formatters.get(provider)!;
    }

    private static createFormatter(provider: ProviderType): MessageFormatter {
        switch (provider) {
            case 'openai':
            case 'siliconflow':
            case 'github':
                return new OpenAIFormatter();

            case 'deepseek':
                return new DeepSeekFormatter();

            case 'zhipu':
                return new GLMFormatter();

            case 'moonshot':
                return new MoonshotFormatter();

            case 'gemini':
            case 'vertex':
                return new GeminiFormatter();

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
