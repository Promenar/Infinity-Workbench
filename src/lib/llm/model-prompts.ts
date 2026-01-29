/**
 * 模型提示词工厂
 * 
 * 架构重构 (2025-01):
 * 已迁移至 `./prompts/` 模块化架构。
 * 此文件仅保留 helper 和兼容性入口。
 */

import { assembleSystemPrompt } from './prompts';

// Export types re-used by other files
export type ModelFamily = 'gemini' | 'deepseek' | 'deepseek-reasoner' | 'glm' | 'moonshot' | 'qwen' | 'openai' | 'anthropic' | 'local' | 'unknown';

/**
 * Infer model family helper
 */
export function inferModelFamily(providerType: string, modelName?: string): ModelFamily {
    const type = providerType.toLowerCase();
    const name = (modelName || '').toLowerCase();

    // Special case for DeepSeek Reasoner
    if (name.includes('reasoner') || name.includes('r1')) {
        if (type === 'deepseek' || name.includes('deepseek')) return 'deepseek-reasoner';
    }

    // 1. Exact Match
    if (type === 'gemini' || type === 'google' || type === 'vertex' || type === 'vertexai') return 'gemini';
    if (type === 'deepseek') return 'deepseek';
    if (type === 'glm' || type === 'zhipu' || type === 'bigmodel') return 'glm';
    if (type === 'moonshot' || type === 'kimi') return 'moonshot';
    if (type === 'qwen' || type === 'alibaba' || type === 'dashscope') return 'qwen';
    if (type === 'openai' || type === 'azure') return 'openai';
    if (type === 'anthropic' || type === 'claude') return 'anthropic';
    if (type === 'ollama' || type === 'local' || type === 'lmstudio') return 'local';

    // 2. Fuzzy Match
    if (name.includes('gemini') || name.includes('palm')) return 'gemini';
    if (name.includes('deepseek')) return 'deepseek';
    if (name.includes('glm') || name.includes('chatglm')) return 'glm';
    if (name.includes('moonshot') || name.includes('kimi')) return 'moonshot';
    if (name.includes('qwen') || name.includes('qwq')) return 'qwen';
    if (name.includes('gpt') || name.includes('o1') || name.includes('o3')) return 'openai';
    if (name.includes('claude')) return 'anthropic';
    if (name.includes('llama') || name.includes('mistral') || name.includes('mixtral')) return 'local';

    return 'unknown';
}

/**
 * Get Continuation Prompt (Legacy / Compatibility)
 */
export function getContinuationPrompt(family: ModelFamily): string {
    switch (family) {
        case 'deepseek-reasoner':
            return `[SYSTEM UPDATE]: The user has approved continuation. Continue logical execution immediately.`;
        case 'deepseek':
        case 'qwen':
            return `### 系统指令：继续执行\n用户已批准继续。请检查历史记录并继续执行下一步。`;
        default:
            return `[SYSTEM: User approved continuation. Continue executing the CURRENT task.]`;
    }
}

/**
 * ✅ NEW ARCHITECTURE ENTRY POINT
 */
export function getModelSpecificEnhancements(
    providerType: string,
    modelName?: string,
    options?: {
        hasNativeSearch?: boolean;
        hasTools?: boolean;
    }
): string {
    const family = inferModelFamily(providerType, modelName);

    // Delegate to the new modular assembler
    return assembleSystemPrompt(family, {
        hasTools: options?.hasTools,
        hasNativeSearch: options?.hasNativeSearch,
        // TODO: In future, pass customPersona from store
    });
}
