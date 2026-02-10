import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { produce } from 'immer';
import { RagConfiguration } from '../types/chat';
import { DEFAULT_KG_PROMPT } from '../lib/rag/defaults';

export type Language = 'en' | 'zh';

interface SettingsState {
  language: Language;
  setLanguage: (lang: Language) => void;

  // User Profile
  userAvatar?: string;
  userName?: string;
  updateUserProfile: (profile: { avatar?: string; name?: string }) => void;

  // Haptics
  hapticsEnabled: boolean;
  setHapticsEnabled: (enabled: boolean) => void;

  // 主题色配置
  accentColor: string;
  setAccentColor: (color: string) => void;

  // 默认模型设置
  defaultSummaryModel?: string; // 总结模型
  defaultTempSessionModel?: string; // 临时会话模型
  defaultEmbeddingModel?: string; // 向量模型
  defaultSpeechModel?: string; // 语音模型
  defaultRerankModel?: string; // 重排序模型
  defaultImageModel?: string; // 绘图模型

  updateDefaultModel: (
    key:
      | 'defaultSummaryModel'
      | 'defaultTempSessionModel'
      | 'defaultEmbeddingModel'
      | 'defaultSpeechModel'
      | 'defaultRerankModel'
      | 'defaultImageModel',
    modelId: string,
  ) => void;

  // RAG Global Settings
  globalRagConfig: RagConfiguration;
  updateGlobalRagConfig: (updates: Partial<RagConfiguration>) => void;

  // Agent/Skill Settings
  maxLoopCount: number;
  setMaxLoopCount: (count: number) => void;
  executionMode: 'auto' | 'semi' | 'manual';
  setExecutionMode: (mode: 'auto' | 'semi' | 'manual') => void;
  skillsConfig: Record<string, boolean>; // skillId -> enabled
  setSkillEnabled: (skillId: string, enabled: boolean) => void;

  // Local Models
  localModelsEnabled: boolean;
  setLocalModelsEnabled: (enabled: boolean) => void;

  // Logging
  loggingEnabled: boolean;
  setLoggingEnabled: (enabled: boolean) => void;

  _hasHydrated: boolean;

  setHasHydrated: (state: boolean) => void;

  // First Launch
  hasLaunched: boolean;
  setHasLaunched: (hasLaunched: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      language: 'zh',
      setLanguage: (lang) => set({ language: lang }),

      userAvatar: undefined,
      userName: undefined,
      updateUserProfile: (profile) =>
        set((state) => ({
          userAvatar: profile.avatar ?? state.userAvatar,
          userName: profile.name ?? state.userName,
        })),

      hasLaunched: false,
      setHasLaunched: (hasLaunched) => set({ hasLaunched }),

      hapticsEnabled: false,
      setHapticsEnabled: (enabled) => set({ hapticsEnabled: enabled }),

      accentColor: '#6366f1',
      setAccentColor: (color) => {
        // Robust Validation: Must be valid 6-digit hex or 3-digit hex
        if (/^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(color)) {
          set({ accentColor: color });
        } else {
          console.warn(`[SettingsStore] Invalid accentColor ignored: ${color}`);
          // Do not save invalid color
        }
      },

      defaultSummaryModel: undefined,
      defaultTempSessionModel: undefined,
      defaultEmbeddingModel: undefined,
      defaultSpeechModel: undefined,
      defaultRerankModel: undefined,
      defaultImageModel: undefined,

      updateDefaultModel: (key, modelId) => set({ [key]: modelId }),

      globalRagConfig: {
        // 切块配置
        docChunkSize: 2000,
        memoryChunkSize: 1000,
        chunkOverlap: 100,

        // 上下文管理
        contextWindow: 20,
        summaryThreshold: 10,
        summaryPrompt:
          'Summarize the following conversation segment concisely, capturing key facts, decisions, and context.',
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
        showStats: false,

        // ===== 高级检索功能（Phase 3） =====

        // Rerank配置
        enableRerank: false, // 默认关闭，用户自行开启
        rerankTopK: 30, // 初召回30条
        rerankFinalK: 8, // 精排后返回8条

        // 查询重写配置
        enableQueryRewrite: false, // 默认关闭
        queryRewriteStrategy: 'multi-query' as const,
        queryRewriteCount: 3,
        queryRewriteModel: undefined, // 未指定时使用defaultSummaryModel

        // 混合检索配置
        enableHybridSearch: false, // 默认关闭
        hybridAlpha: 0.6, // 向量检索权重60%
        hybridBM25Boost: 1.0,

        // 可观测性配置
        showRetrievalProgress: true, // 默认显示进度
        showRetrievalDetails: false, // 默认不显示详情面板
        trackRetrievalMetrics: false, // 默认不记录指标

        // Phase 8 Defaults
        enableKnowledgeGraph: false,
        kgExtractionModel: undefined,
        kgExtractionPrompt: DEFAULT_KG_PROMPT,
        kgMaxDepth: 2,
        kgEntityTypes: ['Person', 'Organization', 'Concept', 'Location'],

        costStrategy: 'summary-first',
        enableIncrementalHash: true,
        enableLocalPreprocess: true,

        // Global Scope Defaults
        activeDocIds: [],
        activeFolderIds: [],
        isGlobal: false,
      },

      updateGlobalRagConfig: (updates) =>
        set((state) => ({
          globalRagConfig: { ...state.globalRagConfig, ...updates },
        })),

      maxLoopCount: 50,
      setMaxLoopCount: (count) => set({ maxLoopCount: count }),
      executionMode: 'semi',
      setExecutionMode: (mode) => set({ executionMode: mode }),
      skillsConfig: {},
      setSkillEnabled: (skillId, enabled) =>
        set((state) => ({
          skillsConfig: { ...state.skillsConfig, [skillId]: enabled },
        })),

      localModelsEnabled: false,

      setLocalModelsEnabled: (enabled) => set({ localModelsEnabled: enabled }),

      loggingEnabled: true,
      setLoggingEnabled: (enabled) => set({ loggingEnabled: enabled }),

      _hasHydrated: false,

      setHasHydrated: (state) => set({ _hasHydrated: state }),
    }),
    {
      name: 'settings-storage-v2',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({
        language: state.language,
        userAvatar: state.userAvatar,
        userName: state.userName,
        hapticsEnabled: state.hapticsEnabled,
        defaultSummaryModel: state.defaultSummaryModel,
        defaultTempSessionModel: state.defaultTempSessionModel,
        defaultEmbeddingModel: state.defaultEmbeddingModel,
        defaultSpeechModel: state.defaultSpeechModel,
        defaultRerankModel: state.defaultRerankModel,
        defaultImageModel: state.defaultImageModel,
        globalRagConfig: state.globalRagConfig,
        accentColor: state.accentColor,
        maxLoopCount: state.maxLoopCount,
        executionMode: state.executionMode,
        skillsConfig: state.skillsConfig,
        localModelsEnabled: state.localModelsEnabled,

        loggingEnabled: state.loggingEnabled,
        hasLaunched: state.hasLaunched,
      }),

      onRehydrateStorage: () => (state) => {
        // Fail-safe: Sanitize hydration
        if (state && (!state.accentColor || !/^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(state.accentColor))) {
          console.warn(`[SettingsStore] Repairing corrupted accentColor: ${state?.accentColor}`);
          if (state) state.accentColor = '#6366f1';
        }
        state?.setHasHydrated(true);
      },
    },
  ),
);
