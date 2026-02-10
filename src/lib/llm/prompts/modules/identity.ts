
/**
 * Identity Module
 * 定义核心身份与系统内核。
 * 🌐 已国际化：所有 Prompt 通过 i18n 字典动态选择语言。
 */

import { getPrompts, type PromptLang } from '../i18n';

export const IdentityModule = {
    /**
     * 内核层：Agent 不可变的"物理法则"。
     * 强制 Markdown 格式、客观性和角色遵守。
     * 
     * 🛡️ REFACTOR (2026-02-07): 移除了硬编码的 "You are Nexara Assistant" 身份。
     * 🌐 I18N (2026-02-11): 所有指令通过 i18n 字典动态选择语言。
     */
    getKernelIdentity(lang?: PromptLang): string {
        return getPrompts(lang).identity.kernel;
    },

    /**
     * 角色层：动态人格注入。
     * @param customSystemPrompt - 用户定义的专家角色 Prompt。
     * @param lang - 语言选项。
     */
    getPersona(customSystemPrompt?: string, lang?: PromptLang): string {
        if (customSystemPrompt && customSystemPrompt.trim().length > 0) {
            return getPrompts(lang).identity.personaWrapper(customSystemPrompt);
        }

        // 🛡️ 不注入默认角色。由 agent-presets.ts 或用户配置控制。
        return '';
    }
};
