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
        confirm: string;
        copied: string;
    };
    sidebar: {
        dashboard: string;
        library: string;
        graph: string;
        settings: string;
        assistants: string;
        manage: string;
        newChat: string;
        signOut: string;
        systemOnline: string;
        menu: string;
        unassigned: string;
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
        processing: string;
        memorized: string;
        failed: string;
        deleteMessageConfirm: string;
    };
    settings: {
        title: string;
        language: string;
        theme: string;
        about: string;
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
            confirm: 'Confirm',
            copied: 'Copied',
        },
        sidebar: {
            dashboard: 'Dashboard',
            library: 'Library',
            graph: 'Knowledge Graph',
            settings: 'Settings',
            assistants: 'ASSISTANTS',
            manage: 'Manage',
            newChat: 'New Chat',
            signOut: 'Sign Out',
            systemOnline: 'System Online',
            menu: 'MENU',
            unassigned: 'UNASSIGNED',
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
            processing: 'Processing',
            memorized: 'Memorized',
            failed: 'Error',
            deleteMessageConfirm: 'Delete this message?',
        },
        settings: {
            title: 'Settings',
            language: 'Language',
            theme: 'Theme',
            about: 'About',
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
            confirm: '确认',
            copied: '已复制',
        },
        sidebar: {
            dashboard: '概览',
            library: '知识库',
            graph: '知识图谱',
            settings: '设置',
            assistants: '智能助手',
            manage: '管理',
            newChat: '新对话',
            signOut: '退出登录',
            systemOnline: '系统在线',
            menu: '菜单',
            unassigned: '未分类',
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
            processing: '处理中',
            memorized: '已记忆',
            failed: '错误',
            deleteMessageConfirm: '确定要删除这条消息吗？',
        },
        settings: {
            title: '设置',
            language: '语言',
            theme: '主题',
            about: '关于',
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
