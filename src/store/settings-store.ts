import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { RagConfiguration } from '../types/chat';

export type Language = 'en' | 'zh';

interface SettingsState {
    language: Language;
    setLanguage: (lang: Language) => void;

    // Haptics
    hapticsEnabled: boolean;
    setHapticsEnabled: (enabled: boolean) => void;

    // 默认模型设置
    defaultSummaryModel?: string; // 总结模型
    defaultTempSessionModel?: string; // 临时会话模型
    defaultEmbeddingModel?: string; // 向量模型
    defaultSpeechModel?: string; // 语音模型
    defaultRerankModel?: string; // 重排序模型

    updateDefaultModel: (key: 'defaultSummaryModel' | 'defaultTempSessionModel' | 'defaultEmbeddingModel' | 'defaultSpeechModel' | 'defaultRerankModel', modelId: string) => void;

    // RAG Global Settings
    globalRagConfig: RagConfiguration;
    updateGlobalRagConfig: (updates: Partial<RagConfiguration>) => void;

    _hasHydrated: boolean;
    setHasHydrated: (state: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
    persist(
        (set) => ({
            language: 'zh',
            setLanguage: (lang) => set({ language: lang }),

            hapticsEnabled: false,
            setHapticsEnabled: (enabled) => set({ hapticsEnabled: enabled }),

            defaultSummaryModel: undefined,
            defaultTempSessionModel: undefined,
            defaultEmbeddingModel: undefined,
            defaultSpeechModel: undefined,

            updateDefaultModel: (key, modelId) => set({ [key]: modelId }),

            globalRagConfig: {
                // 切块配置
                docChunkSize: 800,
                memoryChunkSize: 1000,
                chunkOverlap: 100,

                // 上下文管理
                contextWindow: 20,
                summaryThreshold: 10,
                summaryPrompt: 'Summarize the following conversation segment concisely, capturing key facts, decisions, and context.',
                autoCleanup: true,

                // 检索配置
                memoryLimit: 5,
                memoryThreshold: 0.7,
                docLimit: 8,
                docThreshold: 0.45,

                // 功能开关
                enableMemory: true,
                enableDocs: true,

                // 调试选项
                debugMode: false,
                showStats: false
            },
            updateGlobalRagConfig: (updates) => set((state) => ({
                globalRagConfig: { ...state.globalRagConfig, ...updates }
            })),

            _hasHydrated: false,
            setHasHydrated: (state) => set({ _hasHydrated: state }),
        }),
        {
            name: 'settings-storage-v2',
            storage: createJSONStorage(() => AsyncStorage),
            partialize: (state) => ({
                language: state.language,
                hapticsEnabled: state.hapticsEnabled,
                defaultSummaryModel: state.defaultSummaryModel,
                defaultTempSessionModel: state.defaultTempSessionModel,
                defaultEmbeddingModel: state.defaultEmbeddingModel,
                defaultSpeechModel: state.defaultSpeechModel,
                globalRagConfig: state.globalRagConfig,
            }),
            onRehydrateStorage: () => (state) => {
                state?.setHasHydrated(true);
            },
        }
    )
);
