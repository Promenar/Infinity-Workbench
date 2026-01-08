import React, { useState } from 'react';
import { Plus, Trash2, Eye, EyeOff, Server, Edit2, X, RefreshCw } from 'lucide-react';
import { GlassCard } from '../../components/ui/glass-card';
import { CustomSelect } from '../../components/ui/custom-select';
import { useI18n } from '../../lib/i18n';

// Interfaces
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

interface ModelSectionProps {
    providers: ProviderConfig[];
    onUpdateProvider: (id: string, updates: Partial<ProviderConfig>) => void;
    onDeleteProvider: (id: string) => void;
    onAddProvider: () => void;
    onToggleModel: (providerId: string, modelId: string, enabled: boolean) => void;
    onUpdateCapability: (providerId: string, modelId: string, capability: 'internet' | 'vision' | 'reasoning') => void;
}

export const ModelSection: React.FC<ModelSectionProps> = ({
    providers,
    onUpdateProvider,
    onDeleteProvider,
    onAddProvider,
    onToggleModel,
    onUpdateCapability
}) => {
    const { t } = useI18n();
    const [showKeys, setShowKeys] = useState<Record<string, boolean>>({});
    const [editingModel, setEditingModel] = useState<{ providerId: string, model: ModelConfig } | null>(null);
    const [isNewModel, setIsNewModel] = useState(false);

    const toggleShowKey = (id: string) => {
        setShowKeys(prev => ({ ...prev, [id]: !prev[id] }));
    };

    const handleSaveModel = (model: ModelConfig) => {
        if (!editingModel) return;
        const provider = providers.find(p => p.id === editingModel.providerId);
        if (!provider) return;

        let newModels = [...(provider.models || [])];
        if (isNewModel) {
            newModels.push(model);
        } else {
            newModels = newModels.map(m => m.id === editingModel.model.id ? model : m);
        }

        onUpdateProvider(provider.id, { models: newModels });
        setEditingModel(null);
    };

    const handleDeleteModel = (providerId: string, modelId: string) => {
        if (!confirm('Delete this model?')) return;
        const provider = providers.find(p => p.id === providerId);
        if (!provider) return;
        const newModels = provider.models.filter(m => m.id !== modelId);
        onUpdateProvider(provider.id, { models: newModels });
    };

    const handleFetchModels = async (provider: ProviderConfig) => {
        // Prepare to fetch models from the provider
        console.log("Fetching models for", provider.name);
        // This is a placeholder for the actual API call logic
        // In a real implementation, you would call an API service here
    };

    return (
        <div className="space-y-6">
            <div className="flex justify-between items-end">
                <div className="flex items-center gap-3">
                    <div className="p-2 bg-purple-500/10 rounded-lg text-purple-400">
                        <Server size={24} />
                    </div>
                    <div>
                        <h2 className="text-xl font-bold text-white">{t.settings.models.title}</h2>
                        <p className="text-sm text-zinc-400">{t.settings.models.subtitle}</p>
                    </div>
                </div>
                <button
                    onClick={onAddProvider}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-xl text-sm font-medium transition-colors text-white shadow-lg shadow-indigo-600/20"
                >
                    <Plus size={16} />
                    {t.settings.models.addProvider}
                </button>
            </div>

            <div className="grid gap-6">
                {providers.map((provider) => (
                    <GlassCard key={provider.id} className="p-6 group relative overflow-hidden">
                        <div className="grid grid-cols-1 md:grid-cols-12 gap-6 items-start relative z-10">
                            {/* Name & Type */}
                            <div className="md:col-span-3 space-y-4">
                                <div>
                                    <div className="flex justify-between items-center">
                                        <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Name</label>
                                        <button
                                            onClick={() => onDeleteProvider(provider.id)}
                                            className="p-1.5 text-zinc-500 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                                            title={t.settings.models.deleteProvider}
                                        >
                                            <Trash2 size={16} />
                                        </button>
                                    </div>
                                    <input
                                        type="text"
                                        value={provider.name}
                                        onChange={(e) => onUpdateProvider(provider.id, { name: e.target.value })}
                                        className="w-full bg-[#09090b]/50 border border-white/10 rounded-xl px-3 py-2 mt-1.5 text-sm text-white focus:border-indigo-500/50 outline-none transition-colors"
                                        placeholder="Provider Name"
                                    />
                                </div>
                                <div>
                                    <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Type</label>
                                    <div className="mt-1.5">
                                        <CustomSelect
                                            value={provider.type}
                                            onChange={(val) => onUpdateProvider(provider.id, { type: val })}
                                            options={[
                                                { value: 'openai', label: 'OpenAI Compatible' },
                                                { value: 'anthropic', label: 'Anthropic' },
                                                { value: 'google', label: 'Google Gemini' },
                                                { value: 'ollama', label: 'Ollama' }
                                            ]}
                                        />
                                    </div>
                                </div>
                            </div>

                            {/* Config Fields */}
                            <div className="md:col-span-9 space-y-4">
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    <div>
                                        <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">Base URL</label>
                                        <input
                                            type="text"
                                            value={provider.baseUrl || ''}
                                            onChange={(e) => onUpdateProvider(provider.id, { baseUrl: e.target.value })}
                                            className="w-full bg-[#09090b]/50 border border-white/10 rounded-xl px-3 py-2 mt-1.5 font-mono text-sm text-zinc-400 focus:text-white focus:border-indigo-500/50 outline-none transition-colors"
                                            placeholder="https://api.openai.com/v1"
                                        />
                                    </div>
                                    <div>
                                        <label className="text-[10px] font-bold text-zinc-500 uppercase tracking-wider">API Key</label>
                                        <div className="relative mt-1.5">
                                            <input
                                                type={showKeys[provider.id] ? "text" : "password"}
                                                value={provider.apiKey}
                                                onChange={(e) => onUpdateProvider(provider.id, { apiKey: e.target.value })}
                                                className="w-full bg-[#09090b]/50 border border-white/10 rounded-xl px-3 py-2 font-mono text-sm text-zinc-400 focus:text-white pr-10 focus:border-indigo-500/50 outline-none transition-colors"
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
                                </div>

                                <div className="pt-2">
                                    <div className="flex items-center justify-between mb-2">
                                        <div className="text-xs text-zinc-500 flex items-center gap-2">
                                            <span className={`w-2 h-2 rounded-full ${provider.models && provider.models.length > 0 ? 'bg-emerald-500' : 'bg-amber-500'}`} />
                                            Models detected: <span className="text-zinc-300 font-mono">{provider.models?.length || 0}</span>
                                        </div>
                                        <div className="flex gap-2">
                                            <button
                                                onClick={() => handleFetchModels(provider)}
                                                className="flex items-center gap-1 px-2 py-1 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 text-xs font-medium transition-colors border border-emerald-500/20"
                                            >
                                                <RefreshCw size={12} /> {t.settings.models.fetchModels}
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setEditingModel({ providerId: provider.id, model: { id: '', name: '', contextLength: 4096 } });
                                                    setIsNewModel(true);
                                                }}
                                                className="flex items-center gap-1 px-2 py-1 rounded-lg bg-white/5 hover:bg-white/10 text-xs text-zinc-300 transition-colors"
                                            >
                                                <Plus size={12} /> {t.settings.models.addModel}
                                            </button>
                                        </div>
                                    </div>

                                    {/* Model List with Capabilities */}
                                    {provider.models && provider.models.length > 0 && (
                                        <div className="grid grid-cols-1 gap-2 p-2 bg-[#09090b]/30 rounded-xl border border-white/5 max-h-60 overflow-y-auto custom-scrollbar">
                                            {provider.models.map(m => (
                                                <div key={m.id} className="flex items-center justify-between p-2 rounded-lg hover:bg-white/5 transition-colors group/model">
                                                    <div className="flex items-center gap-3">
                                                        <input
                                                            type="checkbox"
                                                            checked={m.enabled !== false}
                                                            onChange={(e) => onToggleModel(provider.id, m.id, e.target.checked)}
                                                            className="rounded border-zinc-600 bg-zinc-800 text-indigo-500 focus:ring-0 focus:ring-offset-0"
                                                        />
                                                        <div className="flex flex-col">
                                                            <span className={`text-sm font-medium ${m.enabled !== false ? 'text-zinc-200' : 'text-zinc-600'}`}>
                                                                {m.name || m.id}
                                                            </span>
                                                            <span className="text-[10px] text-zinc-600 font-mono">{m.id}</span>
                                                        </div>
                                                    </div>

                                                    <div className="flex items-center gap-2">
                                                        {/* Caps Toggles */}
                                                        <div className="flex gap-2">
                                                            {(['internet', 'vision', 'reasoning'] as const).map(cap => (
                                                                <button
                                                                    key={cap}
                                                                    className={`p-1 rounded text-[10px] uppercase font-bold border ${(m.capabilities as any)?.[cap]
                                                                        ? 'bg-indigo-500/20 text-indigo-400 border-indigo-500/30'
                                                                        : 'bg-zinc-800/50 text-zinc-600 border-zinc-700 opacity-50 group-hover/model:opacity-100'
                                                                        }`}
                                                                    onClick={() => onUpdateCapability(provider.id, m.id, cap)}
                                                                    title={`Toggle ${cap}`}
                                                                >
                                                                    {cap === 'internet' ? 'NET' : cap === 'vision' ? 'VIS' : 'REA'}
                                                                </button>
                                                            ))}
                                                        </div>

                                                        {/* Edit/Delete */}
                                                        <div className="flex gap-1 ml-2 opacity-0 group-hover/model:opacity-100 transition-opacity">
                                                            <button
                                                                onClick={() => {
                                                                    setEditingModel({ providerId: provider.id, model: m });
                                                                    setIsNewModel(false);
                                                                }}
                                                                className="p-1.5 rounded hover:bg-white/10 text-zinc-400 hover:text-white"
                                                            >
                                                                <Edit2 size={12} />
                                                            </button>
                                                            <button
                                                                onClick={() => handleDeleteModel(provider.id, m.id)}
                                                                className="p-1.5 rounded hover:bg-red-500/20 text-zinc-600 hover:text-red-400"
                                                            >
                                                                <Trash2 size={12} />
                                                            </button>
                                                        </div>
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            </div>
                        </div>

                        {/* Provider Actions - Moved to Name Field */}
                    </GlassCard>
                ))}
            </div>

            {/* Edit Model Modal */}
            {editingModel && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
                    <GlassCard className="w-full max-w-md p-6">
                        <div className="flex justify-between items-center mb-6">
                            <h3 className="text-lg font-bold text-white">{isNewModel ? t.settings.models.addModel : t.settings.models.editModel}</h3>
                            <button onClick={() => setEditingModel(null)} className="text-zinc-400 hover:text-white">
                                <X size={20} />
                            </button>
                        </div>

                        <div className="space-y-4">
                            <div>
                                <label className="text-xs font-bold text-zinc-500 uppercase">Model ID (API String)</label>
                                <input
                                    autoFocus
                                    className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 mt-1 text-white font-mono text-sm"
                                    value={editingModel.model.id}
                                    onChange={e => setEditingModel(prev => prev ? ({ ...prev, model: { ...prev.model, id: e.target.value } }) : null)}
                                    placeholder="e.g. gpt-4-turbo-preview"
                                />
                            </div>
                            <div>
                                <label className="text-xs font-bold text-zinc-500 uppercase">Display Name</label>
                                <input
                                    className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 mt-1 text-white text-sm"
                                    value={editingModel.model.name}
                                    onChange={e => setEditingModel(prev => prev ? ({ ...prev, model: { ...prev.model, name: e.target.value } }) : null)}
                                    placeholder="e.g. GPT-4 Turbo"
                                />
                            </div>
                            <div>
                                <label className="text-xs font-bold text-zinc-500 uppercase">Context Length</label>
                                <input
                                    type="number"
                                    className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 mt-1 text-white text-sm"
                                    value={editingModel.model.contextLength || 4096}
                                    onChange={e => setEditingModel(prev => prev ? ({ ...prev, model: { ...prev.model, contextLength: parseInt(e.target.value) } }) : null)}
                                />
                            </div>
                        </div>

                        <div className="flex gap-3 mt-8">
                            <button
                                onClick={() => setEditingModel(null)}
                                className="flex-1 px-4 py-2 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 font-medium transition-colors"
                            >
                                {t.settings.models.cancel}
                            </button>
                            <button
                                onClick={() => handleSaveModel(editingModel.model)}
                                className="flex-1 px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold transition-colors"
                                disabled={!editingModel.model.id}
                            >
                                {t.settings.models.save}
                            </button>
                        </div> {/** End of Edit Model Modal */}
                    </GlassCard>
                </div>
            )}
        </div>
    );
};
