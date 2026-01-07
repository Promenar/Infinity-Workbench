import { create } from 'zustand';
import { persist } from 'zustand/middleware';

type Language = 'en' | 'zh';

type Translations = {
    common: {
        loading: string;
        error: string;
        send: string;
        cancel: string;
        save: string;
        delete: string;
    };
    chat: {
        placeholder: string;
        generating: string;
        newChat: string;
        modelSelector: string;
        rag: string;
        search: string;
        thinking: string;
        ragTooltip: string;
        searchTooltip: string;
        thinkingTooltip: string;
        history: string;
        clearHistory: string;
        confirmClear: string;
        aiGenerated: string;
    };
    status: {
        idle: string;
        thinking: string;
        searching: string;
        generating: string;
    };
};

const translations: Record<Language, Translations> = {
    en: {
        common: {
            loading: 'Loading...',
            error: 'An error occurred',
            send: 'Send',
            cancel: 'Cancel',
            save: 'Save',
            delete: 'Delete',
        },
        chat: {
            placeholder: 'Type a message...',
            generating: 'Generating response...',
            newChat: 'New Chat',
            modelSelector: 'Select Model',
            rag: 'RAG Knowledge',
            search: 'Web Search',
            thinking: 'Deep Thinking',
            ragTooltip: 'Enable retrieval augmented generation',
            searchTooltip: 'Enable web search capabilities',
            thinkingTooltip: 'Enable deep reasoning models',
            history: 'History',
            clearHistory: 'Clear History',
            confirmClear: 'Are you sure you want to clear history?',
            aiGenerated: 'AI GENERATED CONTENT MAY BE INACCURATE',
        },
        status: {
            idle: 'Idle',
            thinking: 'Thinking',
            searching: 'Searching',
            generating: 'Generating',
        },
    },
    zh: {
        common: {
            loading: '加载中...',
            error: '发生错误',
            send: '发送',
            cancel: '取消',
            save: '保存',
            delete: '删除',
        },
        chat: {
            placeholder: '输入消息...',
            generating: '正在生成响应...',
            newChat: '新对话',
            modelSelector: '选择模型',
            rag: 'RAG 知识库',
            search: '联网搜索',
            thinking: '深度思考',
            ragTooltip: '启用知识库检索增强',
            searchTooltip: '启用联网搜索能力',
            thinkingTooltip: '启用深度推理模型',
            history: '历史记录',
            clearHistory: '清除历史',
            confirmClear: '确定要清除历史记录吗？',
            aiGenerated: 'AI 生成内容可能不准确，请核实',
        },
        status: {
            idle: '空闲',
            thinking: '思考中',
            searching: '搜索中',
            generating: '生成中',
        },
    },
};

interface I18nStore {
    language: Language;
    t: Translations;
    setLanguage: (lang: Language) => void;
}

export const useI18n = create<I18nStore>()(
    persist(
        (set) => ({
            language: 'zh', // Default to Chinese as per requirement
            t: translations.zh,
            setLanguage: (lang) => set({ language: lang, t: translations[lang] }),
        }),
        {
            name: 'i18n-storage',
        }
    )
);
