
/**
 * Capability Module
 * 定义 Agent 的能力范围（工具、渲染、知识库）。
 * 🌐 已国际化：所有能力描述通过 i18n 字典动态选择语言。
 */

import { getPrompts, type PromptLang } from '../i18n';

export const CapabilityModule = {
    /**
     * 工具注册表：动态注入的工具说明。
     */
    getToolPhilosophy(hasNativeSearch: boolean = false, lang?: PromptLang): string {
        const prompts = getPrompts(lang);
        const searchNote = hasNativeSearch
            ? prompts.capabilities.searchNoteNative
            : prompts.capabilities.searchNoteManual;

        return prompts.capabilities.toolPhilosophy(searchNote);
    },

    /**
     * 渲染策略：智能路由可视化（Mermaid vs ECharts）。
     */
    getRendererCapabilities(lang?: PromptLang): string {
        return getPrompts(lang).capabilities.renderer;
    },

    /**
     * 知识库策略：RAG 上下文注入说明。
     */
    getKnowledgeContext(lang?: PromptLang): string {
        return getPrompts(lang).capabilities.knowledge;
    }
};
