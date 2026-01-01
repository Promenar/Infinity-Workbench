import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { BillingUsage, TokenMetric } from '../types/chat';

interface AggregateParams {
    modelId: string;
    usage: {
        chatInput?: { count: number; isEstimated: boolean };
        chatOutput?: { count: number; isEstimated: boolean };
        ragSystem?: { count: number; isEstimated: boolean };
    };
}

interface TokenStatsState {
    globalTotal: BillingUsage;
    byModel: Record<string, BillingUsage>;

    trackUsage: (params: AggregateParams) => void;
    resetGlobalStats: () => void;
    resetModelStats: (modelId: string) => void;
}

const initialMetric: TokenMetric = { count: 0, isEstimated: false };

const initialBilling: BillingUsage = {
    chatInput: { ...initialMetric },
    chatOutput: { ...initialMetric },
    ragSystem: { ...initialMetric },
    total: 0,
    costUSD: 0
};

// Helper: 深度合并累加
const accumulate = (current: BillingUsage, incoming: AggregateParams['usage']): BillingUsage => {
    // Deep copy to avoid mutation
    const newStats: BillingUsage = {
        chatInput: { ...current.chatInput },
        chatOutput: { ...current.chatOutput },
        ragSystem: { ...current.ragSystem },
        total: current.total,
        costUSD: current.costUSD
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

    newStats.total =
        newStats.chatInput.count +
        newStats.chatOutput.count +
        newStats.ragSystem.count;

    // Cost calculation placeholder (can be expanded later with rate cards)
    newStats.costUSD = 0;

    return newStats;
};

export const useTokenStatsStore = create<TokenStatsState>()(
    persist(
        (set, get) => ({
            globalTotal: JSON.parse(JSON.stringify(initialBilling)),
            byModel: {},

            trackUsage: ({ modelId, usage }) => set((state) => {
                const currentGlobal = state.globalTotal;
                const currentModel = state.byModel[modelId] || JSON.parse(JSON.stringify(initialBilling));

                return {
                    globalTotal: accumulate(currentGlobal, usage),
                    byModel: {
                        ...state.byModel,
                        [modelId]: accumulate(currentModel, usage)
                    }
                };
            }),

            resetGlobalStats: () => set({
                globalTotal: JSON.parse(JSON.stringify(initialBilling)),
                byModel: {}
            }),

            resetModelStats: (modelId) => set((state) => {
                // Keep global total? Or decrease global total?
                // Per plan: "resetGlobalStats" clears everything. "resetModelStats" (if exposed) should likely just clear that model.
                // But typically resetting a specific model's stats implies removing its contribution.
                // For simplicity in V1, we maintain them somewhat independently or re-calc global.
                // Given the requirement "Factory Reset Statistics" (Global) and "Reset Session" (Local),
                // maybe we don't need granular model reset exposed to user yet.
                // We'll implement strict clearing for now.
                const newByModel = { ...state.byModel };
                delete newByModel[modelId];
                return { byModel: newByModel };
            })
        }),
        {
            name: 'token-stats-storage',
            storage: createJSONStorage(() => AsyncStorage)
        }
    )
);
