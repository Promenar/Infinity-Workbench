import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Save, Plus, Trash2, Eye, EyeOff, Server, Cpu } from 'lucide-react';
import { RagSettingsPanel } from '../components/RagSettingsPanel';

interface ModelConfig {
    id: string;
    name: string;
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
}

export const Settings = () => {
    const [config, setConfig] = useState<ConfigState | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [showKeys, setShowKeys] = useState<Record<string, boolean>>({});

    const [availableModels, setAvailableModels] = useState<{ id: string; name: string }[]>([]);

    useEffect(() => {
        console.log('[Settings] Component Mounted');
        loadConfig();
    }, []);

    useEffect(() => {
        if (config?.providers) {
            const models = config.providers.flatMap(p =>
                (p.models || []).map(m => ({ id: m.id, name: `${p.name} - ${m.name || m.id}` }))
            );
            setAvailableModels(models);
        }
    }, [config]);

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
            alert('Settings saved successfully!');
        } catch (e) {
            alert('Failed to save settings: ' + e);
        } finally {
            setSaving(false);
        }
    };

    const toggleShowKey = (id: string) => {
        setShowKeys(prev => ({ ...prev, [id]: !prev[id] }));
    };

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

    const updateDefault = (key: string, value: string) => {
        if (!config) return;
        setConfig({
            ...config,
            defaults: { ...config.defaults, [key]: value }
        });
    };

    if (loading) return <div className="flex-1 p-8 bg-[#09090b] text-white flex items-center justify-center"><div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div></div>;
    if (!config) return <div className="flex-1 p-8 bg-[#09090b] text-white">Failed to load configuration.</div>;

    return (
        <div className="flex-1 overflow-auto bg-[#09090b] text-white p-8 relative">
            {/* Ambient Background */}
            <div className="absolute top-0 right-0 w-[500px] h-[500px] bg-indigo-500/5 blur-[120px] pointer-events-none" />

            <header className="flex justify-between items-center mb-8 relative z-10">
                <div>
                    <h1 className="text-3xl font-bold text-white tracking-tight">
                        Settings
                    </h1>
                    <p className="text-zinc-400 mt-1">Configure AI providers and default models</p>
                </div>
                <button
                    onClick={handleSave}
                    disabled={saving}
                    className="flex items-center gap-2 px-6 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-xl font-medium transition-colors disabled:opacity-50 shadow-lg shadow-indigo-600/20 active:scale-95"
                >
                    <Save size={18} />
                    {saving ? 'Saving...' : 'Save Changes'}
                </button>
            </header>

            <div className="space-y-8 max-w-4xl relative z-10">

                {/* System Defaults Section */}
                <section className="bg-[#18181b]/40 backdrop-blur-xl rounded-2xl p-6 border border-white/5 shadow-xl">
                    <div className="flex items-center gap-3 mb-6">
                        <div className="p-2 bg-indigo-500/10 rounded-lg text-indigo-400">
                            <Cpu size={24} />
                        </div>
                        <h2 className="text-xl font-semibold">Default Models</h2>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        {[
                            { label: 'Chat Model', key: 'defaultTempSessionModel' },
                            { label: 'Summary Model', key: 'defaultSummaryModel' },
                            { label: 'Embedding Model', key: 'defaultEmbeddingModel' },
                            { label: 'Title Generation', key: 'defaultSpeechModel' },
                        ].map(({ label, key }) => (
                            <div key={key}>
                                <label className="block text-xs font-bold text-zinc-500 uppercase tracking-wider mb-2">{label}</label>
                                <div className="relative group">
                                    <div className="absolute -inset-0.5 bg-indigo-500/20 rounded-xl opacity-0 group-focus-within:opacity-100 transition-opacity blur duration-500" />
                                    <select
                                        value={config.defaults[key as keyof typeof config.defaults] || ''}
                                        onChange={(e) => updateDefault(key, e.target.value)}
                                        className="relative w-full bg-[#18181b]/80 border border-white/10 rounded-xl px-3 py-2.5 focus:border-indigo-500/50 outline-none text-zinc-200 transition-colors appearance-none"
                                    >
                                        <option value="">Select a model...</option>
                                        {availableModels.map(m => (
                                            <option key={m.id} value={m.id}>{m.name}</option>
                                        ))}
                                    </select>
                                    <div className="absolute right-3 top-3 pointer-events-none text-zinc-500">
                                        <svg width="10" height="6" viewBox="0 0 10 6" fill="none" xmlns="http://www.w3.org/2000/svg">
                                            <path d="M1 1L5 5L9 1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                                        </svg>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </section>

                {/* RAG Settings Section */}
                <section className="bg-[#18181b]/40 backdrop-blur-xl rounded-2xl p-0 border border-white/5 overflow-hidden shadow-xl">
                    <RagSettingsPanel />
                </section>

                {/* Providers Section */}
                <section className="space-y-4">
                    <div className="flex justify-between items-end">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-purple-500/10 rounded-lg text-purple-400">
                                <Server size={24} />
                            </div>
                            <h2 className="text-xl font-semibold text-white">Model Providers</h2>
                        </div>
                        <button
                            onClick={addProvider}
                            className="flex items-center gap-2 px-4 py-2 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl text-sm font-medium transition-colors text-zinc-300 hover:text-white"
                        >
                            <Plus size={16} />
                            Add Provider
                        </button>
                    </div>

                    <div className="grid gap-4">
                        {config.providers.map((provider) => (
                            <div key={provider.id} className="bg-[#18181b]/40 backdrop-blur-xl rounded-2xl p-6 border border-white/5 hover:border-indigo-500/30 transition-all group">
                                <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-start">

                                    {/* Name & Type */}
                                    <div className="md:col-span-3 space-y-4">
                                        <div>
                                            <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Name</label>
                                            <input
                                                type="text"
                                                value={provider.name}
                                                onChange={(e) => updateProvider(provider.id, { name: e.target.value })}
                                                className="w-full bg-[#09090b]/50 border border-white/10 rounded-lg px-3 py-2 mt-1.5 text-sm text-white focus:border-indigo-500/50 outline-none transition-colors"
                                                placeholder="Provider Name"
                                            />
                                        </div>
                                        <div>
                                            <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Type</label>
                                            <div className="relative mt-1.5">
                                                <select
                                                    value={provider.type}
                                                    onChange={(e) => updateProvider(provider.id, { type: e.target.value })}
                                                    className="w-full bg-[#09090b]/50 border border-white/10 rounded-lg px-3 py-2 text-sm text-zinc-300 focus:border-indigo-500/50 outline-none appearance-none"
                                                >
                                                    <option value="openai">OpenAI Compatible</option>
                                                    <option value="anthropic">Anthropic</option>
                                                    <option value="google">Google Gemini</option>
                                                    <option value="ollama">Ollama</option>
                                                </select>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Config Fields */}
                                    <div className="md:col-span-8 space-y-4">
                                        <div>
                                            <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Base URL</label>
                                            <input
                                                type="text"
                                                value={provider.baseUrl || ''}
                                                onChange={(e) => updateProvider(provider.id, { baseUrl: e.target.value })}
                                                className="w-full bg-[#09090b]/50 border border-white/10 rounded-lg px-3 py-2 mt-1.5 font-mono text-sm text-zinc-400 focus:text-white focus:border-indigo-500/50 outline-none transition-colors"
                                                placeholder="https://api.openai.com/v1"
                                            />
                                        </div>
                                        <div>
                                            <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">API Key</label>
                                            <div className="relative mt-1.5">
                                                <input
                                                    type={showKeys[provider.id] ? "text" : "password"}
                                                    value={provider.apiKey}
                                                    onChange={(e) => updateProvider(provider.id, { apiKey: e.target.value })}
                                                    className="w-full bg-[#09090b]/50 border border-white/10 rounded-lg px-3 py-2 font-mono text-sm text-zinc-400 focus:text-white pr-10 focus:border-indigo-500/50 outline-none transition-colors"
                                                    placeholder="sk-..."
                                                />
                                                <button
                                                    onClick={() => toggleShowKey(provider.id)}
                                                    className="absolute right-2 top-2.5 text-zinc-500 hover:text-zinc-300"
                                                >
                                                    {showKeys[provider.id] ? <EyeOff size={16} /> : <Eye size={16} />}
                                                </button>
                                            </div>
                                        </div>

                                        <div className="pt-2">
                                            <div className="text-xs text-zinc-500 flex items-center gap-2">
                                                <span className={`w-2 h-2 rounded-full ${provider.models && provider.models.length > 0 ? 'bg-emerald-500' : 'bg-amber-500'}`} />
                                                Models detected: <span className="text-zinc-300 font-mono">{provider.models?.length || 0}</span>
                                            </div>
                                        </div>
                                    </div>

                                    {/* Actions */}
                                    <div className="md:col-span-1 flex justify-end">
                                        <button
                                            onClick={() => deleteProvider(provider.id)}
                                            className="p-2 text-zinc-600 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                                            title="Delete Provider"
                                        >
                                            <Trash2 size={20} />
                                        </button>
                                    </div>

                                </div>
                            </div>
                        ))}
                    </div>
                </section>
            </div>
        </div>
    );
};
