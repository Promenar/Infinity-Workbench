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

    updateDefaultModel: (key: 'defaultSummaryModel' | 'defaultTempSessionModel' | 'defaultEmbeddingModel' | 'defaultSpeechModel', modelId: string) => void;

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
                contextWindow: 10,
                chunkSize: 800,
                chunkOverlap: 100,
                summaryThreshold: 4000,
                summaryPrompt: 'Summarize the following conversation segment concisely, capturing key facts, decisions, and context.',
                enableMemory: true,
                enableDocs: true
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
