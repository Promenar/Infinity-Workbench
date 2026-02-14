import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { produce } from 'immer';
import { ApiProviderType, ModelConfig, ProviderConfig, TokenStats } from './api-types';

export * from './api-types';

interface ApiState {
  providers: ProviderConfig[];
  enabledModels: Record<string, string[]>; // providerId -> modelIds[]
  globalStats: Record<string, TokenStats>; // providerId or modelId -> stats

  // Multi-Engine Search Config
  searchConfig: {
    provider: 'google' | 'tavily' | 'bing' | 'bocha' | 'searxng';
    engineOrder: ('google' | 'tavily' | 'bing' | 'bocha' | 'searxng')[];
    maxResults: number;
    google?: { apiKey: string; cx: string };
    tavily?: { apiKey: string };
    bing?: { apiKey: string };
    bocha?: { apiKey: string };
    searxng?: { baseUrl: string; apiKey?: string };
  };
  setSearchConfig: (config: Partial<ApiState['searchConfig']>) => void;

  // 操作方法
  addProvider: (provider: Omit<ProviderConfig, 'id'>) => void;
  updateProvider: (id: string, updates: Partial<ProviderConfig>) => void;
  deleteProvider: (id: string) => void;
  toggleProvider: (id: string, enabled: boolean) => void;

  toggleModel: (providerId: string, modelId: string, enabled: boolean) => void;
  updateStats: (id: string, tokens: { input: number; output: number }) => void;
  resetStats: (id?: string) => void;
}

export const useApiStore = create<ApiState>()(
  persist(
    (set) => ({
      providers: [],
      enabledModels: {},
      globalStats: {},
      searchConfig: {
        provider: 'google',
        engineOrder: ['google', 'tavily', 'bing', 'bocha', 'searxng'],
        maxResults: 5,
        google: { apiKey: '', cx: '' },
        tavily: { apiKey: '' },
        bing: { apiKey: '' },
        bocha: { apiKey: '' },
        searxng: { baseUrl: '', apiKey: '' },
      },

      setSearchConfig: (config) =>
        set(
          produce((state: ApiState) => {
            state.searchConfig = { ...state.searchConfig, ...config };
          }),
        ),

      addProvider: (provider) =>
        set(
          produce((state: ApiState) => {
            const id = `${provider.type}-${Date.now()}`;
            state.providers.unshift({ ...provider, id });
          }),
        ),

      updateProvider: (id, updates) =>
        set(
          produce((state: ApiState) => {
            const index = state.providers.findIndex((p) => p.id === id);
            if (index !== -1) {
              state.providers[index] = { ...state.providers[index], ...updates };
            }
          }),
        ),

      deleteProvider: (id) =>
        set(
          produce((state: ApiState) => {
            state.providers = state.providers.filter((p) => p.id !== id);
            delete state.enabledModels[id];
            delete state.globalStats[id];
          }),
        ),

      toggleProvider: (id, enabled) =>
        set(
          produce((state: ApiState) => {
            const provider = state.providers.find((p) => p.id === id);
            if (provider) provider.enabled = enabled;
          }),
        ),

      toggleModel: (providerId, modelId, enabled) =>
        set(
          produce((state: ApiState) => {
            // 1. Update enabledModels map
            if (!state.enabledModels[providerId]) {
              state.enabledModels[providerId] = [];
            }
            if (enabled) {
              if (!state.enabledModels[providerId].includes(modelId)) {
                state.enabledModels[providerId].push(modelId);
              }
            } else {
              state.enabledModels[providerId] = state.enabledModels[providerId].filter(
                (m) => m !== modelId,
              );
            }

            // 2. Sync to providers list (SSOT for WebUI)
            const provider = state.providers.find(p => p.id === providerId);
            if (provider) {
              const model = provider.models.find(m => m.id === modelId || m.uuid === modelId);
              if (model) {
                model.enabled = enabled;
              }
            }
          }),
        ),

      updateStats: (id, tokens) =>
        set(
          produce((state: ApiState) => {
            if (!state.globalStats[id]) {
              state.globalStats[id] = { inputTokens: 0, outputTokens: 0, totalTokens: 0 };
            }
            state.globalStats[id].inputTokens += tokens.input;
            state.globalStats[id].outputTokens += tokens.output;
            state.globalStats[id].totalTokens += tokens.input + tokens.output;
            state.globalStats[id].lastUsed = Date.now();
          }),
        ),

      resetStats: (id) =>
        set(
          produce((state: ApiState) => {
            if (id) {
              delete state.globalStats[id];
            } else {
              state.globalStats = {};
            }
          }),
        ),
    }),
    {
      name: 'api-storage-v2',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({
        providers: state.providers,
        enabledModels: state.enabledModels,
        globalStats: state.globalStats,
        searchConfig: state.searchConfig,
      }),
    },
  ),
);
