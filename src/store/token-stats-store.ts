import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { BillingUsage, TokenMetric } from '../types/chat';

interface AggregateParams {
  modelId: string;
  providerId?: string; // 供应商ID，用于按供应商分类
  providerName?: string; // 供应商显示名称
  usage: {
    chatInput?: { count: number; isEstimated: boolean };
    chatOutput?: { count: number; isEstimated: boolean };
    ragSystem?: { count: number; isEstimated: boolean };
  };
}

// 供应商统计结构
export interface ProviderModelStats {
  input: number;
  output: number;
  total: number;
  isEstimated?: boolean;
}

export interface ProviderStats {
  displayName: string;
  total: ProviderModelStats;
  models: Record<string, ProviderModelStats>;
}

interface TokenStatsState {
  globalTotal: BillingUsage;
  byModel: Record<string, BillingUsage>;
  byProvider: Record<string, ProviderStats>; // 按供应商分类的统计

  trackUsage: (params: AggregateParams) => void;
  resetGlobalStats: () => void;
  resetModelStats: (modelId: string) => void;
  resetProviderStats: (providerId: string) => void;
}

const initialMetric: TokenMetric = { count: 0, isEstimated: false };

const initialBilling: BillingUsage = {
  chatInput: { ...initialMetric },
  chatOutput: { ...initialMetric },
  ragSystem: { ...initialMetric },
  total: 0,
  costUSD: 0,
};

const initialProviderStats = (): ProviderStats => ({
  displayName: '',
  total: { input: 0, output: 0, total: 0 },
  models: {},
});

// Helper: 深度合并累加
const accumulate = (current: BillingUsage, incoming: AggregateParams['usage']): BillingUsage => {
  // Deep copy to avoid mutation
  const newStats: BillingUsage = {
    chatInput: { ...current.chatInput },
    chatOutput: { ...current.chatOutput },
    ragSystem: { ...current.ragSystem },
    total: current.total,
    costUSD: current.costUSD,
  };

  if (incoming.chatInput) {
    newStats.chatInput.count += incoming.chatInput.count;
    if (incoming.chatInput.isEstimated) newStats.chatInput.isEstimated = true;
  }
  if (incoming.chatOutput) {
    newStats.chatOutput.count += incoming.chatOutput.count;
    if (incoming.chatOutput.isEstimated) newStats.chatOutput.isEstimated = true;
  }
  if (incoming.ragSystem) {
    newStats.ragSystem.count += incoming.ragSystem.count;
    if (incoming.ragSystem.isEstimated) newStats.ragSystem.isEstimated = true;
  }

  newStats.total = newStats.chatInput.count + newStats.chatOutput.count + newStats.ragSystem.count;

  // Cost calculation placeholder (can be expanded later with rate cards)
  newStats.costUSD = 0;

  return newStats;
};

// Helper: 累加供应商统计
const accumulateProviderStats = (
  current: ProviderStats,
  modelId: string,
  incoming: AggregateParams['usage'],
  displayName?: string,
): ProviderStats => {
  const inputCount = incoming.chatInput?.count || 0;
  const outputCount = incoming.chatOutput?.count || 0;
  const isEstimated = incoming.chatInput?.isEstimated || incoming.chatOutput?.isEstimated || false;

  const newStats: ProviderStats = {
    displayName: displayName || current.displayName || modelId,
    total: {
      input: current.total.input + inputCount,
      output: current.total.output + outputCount,
      total: current.total.total + inputCount + outputCount,
      isEstimated: current.total.isEstimated || isEstimated,
    },
    models: { ...current.models },
  };

  // 更新模型统计
  const existingModel = newStats.models[modelId] || { input: 0, output: 0, total: 0 };
  newStats.models[modelId] = {
    input: existingModel.input + inputCount,
    output: existingModel.output + outputCount,
    total: existingModel.total + inputCount + outputCount,
    isEstimated: existingModel.isEstimated || isEstimated,
  };

  return newStats;
};

export const useTokenStatsStore = create<TokenStatsState>()(
  persist(
    (set, get) => ({
      globalTotal: JSON.parse(JSON.stringify(initialBilling)),
      byModel: {},
      byProvider: {},

      trackUsage: ({ modelId, providerId, providerName, usage }) =>
        set((state) => {
          const currentGlobal = state.globalTotal;
          const currentModel = state.byModel[modelId] || JSON.parse(JSON.stringify(initialBilling));

          // 更新全局统计和按模型统计
          const newState: Partial<TokenStatsState> = {
            globalTotal: accumulate(currentGlobal, usage),
            byModel: {
              ...state.byModel,
              [modelId]: accumulate(currentModel, usage),
            },
          };

          // 如果提供了 providerId，更新按供应商统计
          if (providerId) {
            const currentProvider = state.byProvider[providerId] || initialProviderStats();
            newState.byProvider = {
              ...state.byProvider,
              [providerId]: accumulateProviderStats(currentProvider, modelId, usage, providerName),
            };
          }

          return newState;
        }),

      resetGlobalStats: () =>
        set({
          globalTotal: JSON.parse(JSON.stringify(initialBilling)),
          byModel: {},
          byProvider: {},
        }),

      resetModelStats: (modelId) =>
        set((state) => {
          const newByModel = { ...state.byModel };
          delete newByModel[modelId];
          return { byModel: newByModel };
        }),

      resetProviderStats: (providerId) =>
        set((state) => {
          const newByProvider = { ...state.byProvider };
          delete newByProvider[providerId];
          return { byProvider: newByProvider };
        }),
    }),
    {
      name: 'token-stats-storage',
      storage: createJSONStorage(() => AsyncStorage),
      onRehydrateStorage: () => (state) => {
        // Fail-safe: 确保 hydrated 数据结构完整，防止损坏数据导致崩溃
        if (state) {
          // 确保 globalTotal 存在且结构正确
          if (!state.globalTotal) {
            console.warn('[TokenStatsStore] Repairing missing globalTotal');
            state.globalTotal = JSON.parse(JSON.stringify(initialBilling));
          } else {
            // 确保 globalTotal 的所有字段存在
            if (!state.globalTotal.chatInput) {
              state.globalTotal.chatInput = { ...initialMetric };
            }
            if (!state.globalTotal.chatOutput) {
              state.globalTotal.chatOutput = { ...initialMetric };
            }
            if (!state.globalTotal.ragSystem) {
              state.globalTotal.ragSystem = { ...initialMetric };
            }
            if (typeof state.globalTotal.total !== 'number') {
              state.globalTotal.total = 0;
            }
            if (typeof state.globalTotal.costUSD !== 'number') {
              state.globalTotal.costUSD = 0;
            }
          }
          // 确保 byModel 存在
          if (!state.byModel) {
            console.warn('[TokenStatsStore] Repairing missing byModel');
            state.byModel = {};
          }
          // 确保 byProvider 存在
          if (!state.byProvider) {
            console.warn('[TokenStatsStore] Repairing missing byProvider');
            state.byProvider = {};
          }
        }
      },
    },
  ),
);
