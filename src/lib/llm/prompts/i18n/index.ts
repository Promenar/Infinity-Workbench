/**
 * Prompt 国际化入口
 *
 * 提供 getPromptLang() 获取当前语言，以及
 * getPrompts(lang) 获取对应语言的完整 Prompt 字典。
 *
 * 设计决策：
 * - 不复用 UI 层的 i18n.ts（该文件已近 2000 行，且 Prompt 结构与 UI 文本差异极大）。
 * - 采用独立字典文件（zh.ts / en.ts），每个模块的 Prompt 在字典中保持扁平化。
 */

import { useSettingsStore } from '../../../../store/settings-store';
import { zhPrompts } from './zh';
import { enPrompts } from './en';

export type PromptLang = 'zh' | 'en';

/**
 * Prompt 字典类型 — 使用宽松类型避免 zhPrompts 与 enPrompts 之间的字面量类型不兼容
 * 以 zhPrompts 的结构为基准，但所有 string 字面量宽化为 string
 */
export interface PromptDict {
    identity: {
        kernel: string;
        personaWrapper: (prompt: string) => string;
    };
    protocols: {
        thinking: string;
        task: (hasTaskTool: boolean) => string;
        formatting: string;
    };
    capabilities: {
        toolPhilosophy: (searchNote: string) => string;
        searchNoteNative: string;
        searchNoteManual: string;
        renderer: string;
        knowledge: string;
    };
    context: {
        systemMetadata: (timeString: string) => string;
        taskStatus: {
            header: string;
            currentTask: string;
            lastAction: string;
            immediateGoal: string;
            toolCompleted: (name: string) => string;
            toolWaiting: (names: string) => string;
            userInput: string;
            noAction: string;
            allCompleted: string;
            stepProgress: (current: string, total: number) => string;
            criticalInstruction: string;
        };
        tools: {
            header: string;
            intro: string;
            executionRules: string;
            scopeWarning: string;
        };
        toolsDisabled: string;
        taskContext: {
            header: string;
            important: string;
        };
        intervention: (text: string) => string;
    };
    rag: {
        kgDefaultPrompt: string;
        kgFreeModePrompt: string;
        kgDomainAutoPrompt: string;
        kgFallback: (entityTypes: string) => string;
        queryRewriter: {
            hyde: (query: string) => string;
            multiQuery: (query: string, count: number) => string;
            expansion: (query: string) => string;
        };
        defaultSummaryPrompt: string;
    };
    continuation: {
        reasoner: string;
        standard: string;
        generic: string;
    };
}

const dictMap: Record<PromptLang, PromptDict> = {
    zh: zhPrompts,
    en: enPrompts,
};

/**
 * 获取当前用户设置的语言
 * 在非 React 环境（纯逻辑层）中安全调用
 */
export function getPromptLang(): PromptLang {
    try {
        return useSettingsStore.getState().language || 'zh';
    } catch {
        return 'zh';
    }
}

/**
 * 获取指定语言的完整 Prompt 字典
 */
export function getPrompts(lang?: PromptLang): PromptDict {
    const effectiveLang = lang || getPromptLang();
    return dictMap[effectiveLang] || dictMap.zh;
}
