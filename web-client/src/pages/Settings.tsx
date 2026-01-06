import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Save, Plus, Trash2, Eye, EyeOff, Server, Cpu } from 'lucide-react';

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

    if (loading) return <div className="flex-1 p-8 bg-slate-900 text-white">Loading configuration...</div>;
    if (!config) return <div className="flex-1 p-8 bg-slate-900 text-white">Failed to load configuration.</div>;

    return (
        <div className="flex-1 overflow-auto bg-slate-900 text-slate-100 p-8">
            <header className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-400 to-indigo-400 bg-clip-text text-transparent">
                        Settings
                    </h1>
                    <p className="text-slate-400 mt-1">Configure AI providers and default models</p>
                </div>
                <button
                    onClick={handleSave}
                    disabled={saving}
                    className="flex items-center gap-2 px-6 py-2 bg-blue-600 hover:bg-blue-500 rounded-lg font-medium transition-colors disabled:opacity-50"
                >
                    <Save size={18} />
                    {saving ? 'Saving...' : 'Save Changes'}
                </button>
            </header>

            <div className="space-y-8 max-w-4xl">

                {/* System Defaults Section */}
                <section className="bg-slate-800/50 rounded-xl p-6 border border-slate-700/50">
                    <div className="flex items-center gap-3 mb-6">
                        <Cpu className="text-blue-400" size={24} />
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
                                <label className="block text-sm font-medium text-slate-400 mb-2">{label}</label>
                                <select
                                    value={config.defaults[key as keyof typeof config.defaults] || ''}
                                    onChange={(e) => updateDefault(key, e.target.value)}
                                    className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 focus:ring-2 focus:ring-blue-500 outline-none"
                                >
                                    <option value="">Select a model...</option>
                                    {availableModels.map(m => (
                                        <option key={m.id} value={m.id}>{m.name}</option>
                                    ))}
                                </select>
                            </div>
                        ))}
                    </div>
                </section>

                {/* Providers Section */}
                <section className="space-y-4">
                    <div className="flex justify-between items-end">
                        <div className="flex items-center gap-3">
                            <Server className="text-purple-400" size={24} />
                            <h2 className="text-xl font-semibold">Model Providers</h2>
                        </div>
                        <button
                            onClick={addProvider}
                            className="flex items-center gap-2 px-4 py-2 bg-slate-700 hover:bg-slate-600 rounded-lg text-sm font-medium transition-colors"
                        >
                            <Plus size={16} />
                            Add Provider
                        </button>
                    </div>

                    <div className="grid gap-4">
                        {config.providers.map((provider) => (
                            <div key={provider.id} className="bg-slate-800/50 rounded-xl p-6 border border-slate-700/50 hover:border-slate-600 transition-colors">
                                <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-start">

                                    {/* Name & Type */}
                                    <div className="md:col-span-3 space-y-3">
                                        <div>
                                            <label className="text-xs text-slate-500 uppercase tracking-wider">Name</label>
                                            <input
                                                type="text"
                                                value={provider.name}
                                                onChange={(e) => updateProvider(provider.id, { name: e.target.value })}
                                                className="w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 mt-1"
                                                placeholder="Provider Name"
                                            />
                                        </div>
                                        <div>
                                            <label className="text-xs text-slate-500 uppercase tracking-wider">Type</label>
                                            <select
                                                value={provider.type}
                                                onChange={(e) => updateProvider(provider.id, { type: e.target.value })}
                                                className="w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 mt-1"
                                            >
                                                <option value="openai">OpenAI Compatible</option>
                                                <option value="anthropic">Anthropic</option>
                                                <option value="google">Google Gemini</option>
                                                <option value="ollama">Ollama</option>
                                            </select>
                                        </div>
                                    </div>

                                    {/* Config Fields */}
                                    <div className="md:col-span-8 space-y-3">
                                        <div>
                                            <label className="text-xs text-slate-500 uppercase tracking-wider">Base URL</label>
                                            <input
                                                type="text"
                                                value={provider.baseUrl || ''}
                                                onChange={(e) => updateProvider(provider.id, { baseUrl: e.target.value })}
                                                className="w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 mt-1 font-mono text-sm"
                                                placeholder="https://api.openai.com/v1"
                                            />
                                        </div>
                                        <div>
                                            <label className="text-xs text-slate-500 uppercase tracking-wider">API Key</label>
                                            <div className="relative mt-1">
                                                <input
                                                    type={showKeys[provider.id] ? "text" : "password"}
                                                    value={provider.apiKey}
                                                    onChange={(e) => updateProvider(provider.id, { apiKey: e.target.value })}
                                                    className="w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 font-mono text-sm pr-10"
                                                    placeholder="sk-..."
                                                />
                                                <button
                                                    onClick={() => toggleShowKey(provider.id)}
                                                    className="absolute right-2 top-2.5 text-slate-500 hover:text-slate-300"
                                                >
                                                    {showKeys[provider.id] ? <EyeOff size={16} /> : <Eye size={16} />}
                                                </button>
                                            </div>
                                        </div>

                                        <div className="pt-2">
                                            <div className="text-xs text-slate-500 mb-1">Models: {provider.models?.length || 0} configured</div>
                                        </div>
                                    </div>

                                    {/* Actions */}
                                    <div className="md:col-span-1 flex justify-end">
                                        <button
                                            onClick={() => deleteProvider(provider.id)}
                                            className="p-2 text-slate-500 hover:text-red-400 hover:bg-slate-700/50 rounded-lg transition-colors"
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
