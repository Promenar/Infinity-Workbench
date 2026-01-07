import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Save, Globe, Server, Database, HardDrive, BarChart3, ChevronRight, Settings as SettingsIcon, Search, Network } from 'lucide-react';
import { useI18n } from '../lib/i18n';
import { clsx } from 'clsx';
import { AnimatePresence, motion } from 'framer-motion';

// Section Components
import { GeneralSection } from './settings/GeneralSection';
import { ModelSection } from './settings/ModelSection';
import { RagBasicSettings } from './settings/RagBasicSettings';
import { RagRetrievalSettings } from './settings/RagRetrievalSettings';
import { RagKgSettings } from './settings/RagKgSettings';
import { BackupSection } from './settings/BackupSection';
import { UsageSection } from './settings/UsageSection';

// Types (Ideally move to types/settings.ts)
interface ModelConfig {
    id: string;
    name: string;
    enabled?: boolean;
    capabilities?: {
        internet?: boolean;
        vision?: boolean;
        reasoning?: boolean;
    };
    contextLength?: number;
}

interface ProviderConfig {
    id: string;
    type: string;
    name: string;
    baseUrl?: string;
    apiKey: string;
    enabled: boolean;
    models: ModelConfig[];
}

interface ConfigState {
    defaults: {
        defaultSummaryModel?: string;
        defaultTempSessionModel?: string;
        defaultEmbeddingModel?: string;
        defaultSpeechModel?: string;
        defaultRerankModel?: string;
    };
    providers: ProviderConfig[];
    rag?: any; // Add rag prop to ConfigState
}

type SettingsSection = 'general' | 'models' | 'rag-basic' | 'rag-retrieval' | 'rag-kg' | 'backup' | 'usage';

export const Settings = () => {
    const { t } = useI18n();
    const [activeSection, setActiveSection] = useState<SettingsSection>('general');
    const [config, setConfig] = useState<ConfigState | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        console.log('[Settings] Component Mounted');
        loadConfig();
    }, []);

    const loadConfig = async () => {
        try {
            const data = await workbenchClient.getConfig();
            console.log('[Settings] Config loaded:', data);
            setConfig(data);
        } catch (e) {
            console.error('Failed to load config', e);
        } finally {
            setLoading(false);
        }
    };

    const handleSave = async () => {
        if (!config) return;
        setSaving(true);
        try {
            await workbenchClient.updateConfig(config);
            // alert('Settings saved successfully!'); // Use a toast preferably
        } catch (e) {
            console.error('Failed to save settings:', e);
            alert('Failed to save settings: ' + e);
        } finally {
            setSaving(false);
        }
    };

    // Updates the RagConfig update logic
    const updateRagConfig = (key: string, value: any) => {
        if (!config) return;
        setConfig({
            ...config,
            rag: {
                ...config.rag,
                [key]: value
            }
        });
    };

    // --- Provider/Model Handlers ---
    const updateProvider = (id: string, updates: Partial<ProviderConfig>) => {
        if (!config) return;
        setConfig({
            ...config,
            providers: config.providers.map(p => p.id === id ? { ...p, ...updates } : p)
        });
    };

    const deleteProvider = (id: string) => {
        if (!config || !window.confirm('Delete this provider?')) return;
        setConfig({
            ...config,
            providers: config.providers.filter(p => p.id !== id)
        });
    };

    const addProvider = () => {
        if (!config) return;
        const newProvider: ProviderConfig = {
            id: `new-${Date.now()}`,
            type: 'openai',
            name: 'New Provider',
            apiKey: '',
            baseUrl: '',
            enabled: true,
            models: []
        };
        setConfig({
            ...config,
            providers: [...config.providers, newProvider]
        });
    };

    const toggleModel = (providerId: string, modelId: string, enabled: boolean) => {
        if (!config) return;
        setConfig({
            ...config,
            providers: config.providers.map(p => {
                if (p.id !== providerId) return p;
                return {
                    ...p,
                    models: p.models.map(m =>
                        m.id === modelId ? { ...m, enabled } : m
                    )
                };
            })
        });
    };

    const updateModelCapability = (providerId: string, modelId: string, capability: 'internet' | 'vision' | 'reasoning') => {
        if (!config) return;
        setConfig({
            ...config,
            providers: config.providers.map(p => {
                if (p.id !== providerId) return p;
                return {
                    ...p,
                    models: p.models.map(m => {
                        if (m.id !== modelId) return m;
                        const currentCaps = m.capabilities || {};
                        return {
                            ...m,
                            capabilities: {
                                ...currentCaps,
                                [capability]: !currentCaps[capability]
                            }
                        };
                    })
                };
            })
        });
    };



    if (loading) return (
        <div className="flex-1 flex items-center justify-center h-full">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
        </div>
    );

    if (!config) return (
        <div className="flex-1 flex items-center justify-center h-full text-zinc-500">
            Failed to load configuration.
        </div>
    );

    const menuItems = [
        { id: 'general', icon: Globe, label: t.settings.language },
        { id: 'models', icon: Server, label: t.settings.models.title },

        // Split RAG Sections
        { id: 'rag-basic', icon: Database, label: t.settings.ragBasic?.title || 'RAG Basic' },
        { id: 'rag-retrieval', icon: Search, label: t.settings.ragRetrieval?.title || 'Retrieval' },
        { id: 'rag-kg', icon: Network, label: t.settings.ragKg?.title || 'Knowledge Graph' },

        { id: 'backup', icon: HardDrive, label: t.settings.backup.title },
        { id: 'usage', icon: BarChart3, label: t.settings.usage.title },
    ];

    return (
        <div className="flex h-full overflow-hidden bg-[#09090b] text-white relative">
            {/* Ambient Background */}
            <div className="absolute top-0 right-0 w-[600px] h-[600px] bg-indigo-500/5 blur-[120px] pointer-events-none" />
            <div className="absolute bottom-0 left-0 w-[400px] h-[400px] bg-pink-500/5 blur-[100px] pointer-events-none" />

            <div className="w-full h-full flex max-w-7xl mx-auto p-4 md:p-8 gap-8 relative z-10">

                {/* Sidebar Navigation */}
                <div className="w-64 shrink-0 flex flex-col gap-2">
                    <div className="mb-6 px-2">
                        <h1 className="text-2xl font-bold flex items-center gap-2">
                            <SettingsIcon className="text-indigo-500" />
                            {t.settings.title}
                        </h1>
                    </div>

                    {menuItems.map((item) => (
                        <button
                            key={item.id}
                            onClick={() => setActiveSection(item.id as SettingsSection)}
                            className={clsx(
                                "flex items-center gap-3 px-4 py-3 rounded-xl transition-all text-sm font-medium",
                                activeSection === item.id
                                    ? "bg-indigo-600/10 text-indigo-400 ring-1 ring-indigo-500/50 shadow-lg shadow-indigo-900/20"
                                    : "text-zinc-400 hover:bg-white/5 hover:text-white"
                            )}
                        >
                            <item.icon size={18} />
                            <span>{item.label}</span>
                            {activeSection === item.id && <ChevronRight size={14} className="ml-auto opacity-50" />}
                        </button>
                    ))}

                    <div className="mt-auto pt-6 border-t border-white/5 px-2">
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className={clsx(
                                "w-full flex items-center justify-center gap-2 px-4 py-3 rounded-xl font-bold transition-all shadow-lg active:scale-95",
                                saving
                                    ? "bg-emerald-500/20 text-emerald-400 cursor-wait"
                                    : "bg-indigo-600 hover:bg-indigo-500 text-white shadow-indigo-600/20"
                            )}
                        >
                            <Save size={18} />
                            {saving ? t.common.save + '...' : t.common.save}
                        </button>
                    </div>
                </div>

                {/* Main Content Area */}
                <div className="flex-1 h-full overflow-y-auto custom-scrollbar pr-2">
                    <AnimatePresence mode="wait">
                        <motion.div
                            key={activeSection}
                            initial={{ opacity: 0, y: 10 }}
                            animate={{ opacity: 1, y: 0 }}
                            exit={{ opacity: 0, y: -10 }}
                            transition={{ duration: 0.2 }}
                            className="pb-10"
                        >
                            {activeSection === 'general' && <GeneralSection />}

                            {activeSection === 'models' && (
                                <ModelSection
                                    providers={config.providers}
                                    onUpdateProvider={updateProvider}
                                    onDeleteProvider={deleteProvider}
                                    onAddProvider={addProvider}
                                    onToggleModel={toggleModel}
                                    onUpdateCapability={updateModelCapability}
                                />
                            )}

                            {activeSection === 'rag-basic' && (
                                <RagBasicSettings config={config.rag} onChange={updateRagConfig} />
                            )}

                            {activeSection === 'rag-retrieval' && (
                                <RagRetrievalSettings config={config.rag} onChange={updateRagConfig} />
                            )}

                            {activeSection === 'rag-kg' && (
                                <RagKgSettings config={config.rag} onChange={updateRagConfig} />
                            )}

                            {activeSection === 'backup' && <BackupSection />}

                            {activeSection === 'usage' && <UsageSection />}

                        </motion.div>
                    </AnimatePresence>
                </div>
            </div>
        </div>
    );
};
