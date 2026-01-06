import { useSettingsStore } from '../../../store/settings-store';
import { useApiStore, ProviderConfig, ModelConfig } from '../../../store/api-store';
import { RouterContext } from '../WorkbenchRouter';

export const ConfigController = {
    async getConfig(_: any, context: RouterContext) {
        const settingsStore = useSettingsStore.getState();
        const apiStore = useApiStore.getState();

        const config = {
            defaults: {
                defaultSummaryModel: settingsStore.defaultSummaryModel,
                defaultTempSessionModel: settingsStore.defaultTempSessionModel,
                defaultEmbeddingModel: settingsStore.defaultEmbeddingModel,
                defaultSpeechModel: settingsStore.defaultSpeechModel,
                defaultRerankModel: settingsStore.defaultRerankModel,
            },
            rag: settingsStore.globalRagConfig,
            providers: apiStore.providers,
        };
        console.log('[Config] getConfig returning:', JSON.stringify(config, null, 2));
        return config;
    },

    async updateConfig(payload: any, context: RouterContext) {
        const { defaults, rag, providers } = payload;

        // Update Defaults
        if (defaults) {
            const setModel = useSettingsStore.getState().updateDefaultModel;
            if (defaults.defaultSummaryModel !== undefined) setModel('defaultSummaryModel', defaults.defaultSummaryModel);
            if (defaults.defaultTempSessionModel !== undefined) setModel('defaultTempSessionModel', defaults.defaultTempSessionModel);
            if (defaults.defaultEmbeddingModel !== undefined) setModel('defaultEmbeddingModel', defaults.defaultEmbeddingModel);
            if (defaults.defaultSpeechModel !== undefined) setModel('defaultSpeechModel', defaults.defaultSpeechModel);
            if (defaults.defaultRerankModel !== undefined) setModel('defaultRerankModel', defaults.defaultRerankModel);
        }

        // Update RAG Config
        if (rag) {
            useSettingsStore.getState().updateGlobalRagConfig(rag);
        }

        // Update Providers
        // Strategy: Full Sync (Add/Update/Delete)
        if (Array.isArray(providers)) {
            const store = useApiStore.getState();
            const currentProviders = store.providers;
            const incomingIds = new Set(providers.map((p: any) => p.id));

            // 1. Update or Add
            providers.forEach((p: ProviderConfig) => {
                const exists = currentProviders.find(cp => cp.id === p.id);
                if (exists) {
                    store.updateProvider(p.id, p);
                } else {
                    store.addProvider(p);
                }
            });

            // 2. Delete removed
            currentProviders.forEach(cp => {
                if (!incomingIds.has(cp.id)) {
                    store.deleteProvider(cp.id);
                }
            });
        }

        return { success: true };
    }
};
