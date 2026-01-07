import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { produce } from 'immer';

// 支持的服务商类型
export type ApiProviderType =
  | 'openai'
  | 'anthropic'
  | 'google' // VertexAI
  | 'gemini' // Direct Gemini API
  | 'deepseek'
  | 'moonshot'
  | 'zhipu'
  | 'siliconflow'
  | 'github'
  | 'cloudflare'
  | 'github-copilot'
  | 'local';

// 模型能力标识
export interface ModelCapabilities {
  vision?: boolean;
  internet?: boolean;
  reasoning?: boolean; // 思考模型（如 R1, o1）
  tools?: boolean;
}

// 模型配置接口
export interface ModelConfig {
  uuid: string; // 内部稳定标识符，用于 React 渲染 key
  id: string; // API 调用参数 (如 "gpt-4o")
  name: string; // 显示名称
  type?: 'chat' | 'reasoning' | 'image' | 'embedding' | 'rerank';
  contextLength?: number;
  capabilities: ModelCapabilities;
  enabled: boolean;
  isAutoFetched?: boolean;
}

// 服务商配置接口
export interface ProviderConfig {
  id: string;
  name: string;
  type: ApiProviderType;
  apiKey: string;
  baseUrl?: string;
  enabled: boolean;
  models: ModelConfig[]; // 模型列表
  // VertexAI 特定字段
  vertexProject?: string;
  vertexLocation?: string;
  vertexKeyJson?: string;
}

// 模型统计接口
export interface TokenStats {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  lastUsed?: number;
}

interface ApiState {
  providers: ProviderConfig[];
  enabledModels: Record<string, string[]>; // providerId -> modelIds[]
  globalStats: Record<string, TokenStats>; // providerId or modelId -> stats

  // Google Custom Search Config
  googleSearchConfig?: {
    apiKey: string;
    cx: string;
  };
  setGoogleSearchConfig: (config: { apiKey: string; cx: string }) => void;

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
      googleSearchConfig: undefined,

      setGoogleSearchConfig: (config) =>
        set(
          produce((state: ApiState) => {
            state.googleSearchConfig = config;
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
      name: 'api-storage-v2', // 升级版本以避免与旧配置冲突
      storage: createJSONStorage(() => AsyncStorage),
    },
  ),
);
