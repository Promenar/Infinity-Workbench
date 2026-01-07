import { useTokenStatsStore } from '../../../store/token-stats-store';
import { RouterContext } from '../WorkbenchRouter';

export const StatsController = {
    async getStats(_: any, context: RouterContext) {
        const store = useTokenStatsStore.getState();
        return {
            globalTotal: store.globalTotal,
            byModel: store.byModel
        };
    },

    async resetStats(payload: { modelId?: string }, context: RouterContext) {
        const store = useTokenStatsStore.getState();
        if (payload && payload.modelId) {
            store.resetModelStats(payload.modelId);
        } else {
            store.resetGlobalStats();
        }
        return { success: true };
    }
};
