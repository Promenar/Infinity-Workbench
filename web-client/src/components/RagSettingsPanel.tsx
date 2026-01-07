import { useState, useEffect } from 'react';
import { Settings2, Save, RotateCcw, Thermometer, Layers, Search, Database } from 'lucide-react';
import { workbenchClient } from '../services/WorkbenchClient';
import clsx from 'clsx';

interface RagConfig {
    enabled: boolean;
    topK: number;
    similarityThreshold: number;
    rerankModel: string;
    temperature: number; // Generation temp override
    maxTokens: number;
    useHybridSearch: boolean;
    useQueryRewriting: boolean;
}

interface RagSettingsPanelProps {
    className?: string;
    onClose?: () => void;
}

export function RagSettingsPanel({ className, onClose }: RagSettingsPanelProps) {
    const [config, setConfig] = useState<RagConfig>({
        enabled: true,
        topK: 5,
        similarityThreshold: 0.7,
        rerankModel: 'none',
        temperature: 0.7,
        maxTokens: 2000,
        useHybridSearch: true,
        useQueryRewriting: false
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [availableRerankers] = useState<string[]>(['none', 'bge-reranker-v2-m3', 'ms-marco-MiniLM-L-6-v2']); // Mock for now or fetch

    useEffect(() => {
        loadConfig();
    }, []);

    const loadConfig = async () => {
        setLoading(true);
        try {
            const data = await workbenchClient.getConfig();
            if (data?.rag) {
                setConfig({
                    enabled: data.rag.enabled ?? true,
                    topK: data.rag.topK ?? 5,
                    similarityThreshold: data.rag.similarityThreshold ?? 0.7,
                    rerankModel: data.rag.rerankModel ?? 'none',
                    temperature: data.rag.temperature ?? 0.7,
                    maxTokens: data.rag.maxTokens ?? 2000,
                    useHybridSearch: data.rag.useHybridSearch ?? true,
                    useQueryRewriting: data.rag.useQueryRewriting ?? false
                });
            }
        } catch (e) {
            console.error('Failed to load RAG config', e);
        } finally {
            setLoading(false);
        }
    };

    const handleSave = async () => {
        setSaving(true);
        try {
            await workbenchClient.updateConfig({ rag: config });
            // alert('RAG Settings updated');
            if (onClose) onClose();
        } catch (e) {
            console.error('Failed to save RAG config', e);
            alert('Failed to save settings');
        } finally {
            setSaving(false);
        }
    };

    const handleChange = (key: keyof RagConfig, value: any) => {
        setConfig(prev => ({ ...prev, [key]: value }));
    };

    if (loading) return <div className="p-8 text-center text-zinc-500 animate-pulse">Loading settings...</div>;

    return (
        <div className={clsx("bg-[#18181b]/90 backdrop-blur-xl border border-white/10 rounded-2xl overflow-hidden shadow-2xl flex flex-col max-h-[80vh]", className)}>
            {/* Header */}
            <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between bg-white/5">
                <div className="flex items-center gap-2">
                    <Database className="text-indigo-400" size={20} />
                    <h2 className="font-bold text-white text-lg">Retrieval Settings</h2>
                </div>
                {onClose && (
                    <button onClick={onClose} className="p-1 hover:bg-white/10 rounded-lg transition-colors">
                        <Settings2 className="text-zinc-400" size={20} />
                    </button>
                )}
            </div>

            {/* Content */}
            <div className="p-6 space-y-6 overflow-y-auto custom-scrollbar flex-1">

                {/* Retrieval Strategy */}
                <div className="space-y-4">
                    <h3 className="text-xs font-bold text-zinc-500 uppercase tracking-wider">Retrieval Strategy</h3>

                    <div className="grid grid-cols-2 gap-4">
                        <div className="space-y-2">
                            <label className="text-sm font-medium text-zinc-300 flex items-center gap-2">
                                <Search size={14} className="text-zinc-500" />
                                Top K Documents
                            </label>
                            <input
                                type="number"
                                min={1} max={20}
                                value={config.topK}
                                onChange={(e) => handleChange('topK', parseInt(e.target.value))}
                                className="w-full bg-black/20 border border-white/10 rounded-lg px-3 py-2 text-white focus:border-indigo-500 outline-none transition-colors"
                            />
                            <p className="text-xs text-zinc-600">Number of chunks to retrieve.</p>
                        </div>

                        <div className="space-y-2">
                            <label className="text-sm font-medium text-zinc-300 flex items-center gap-2">
                                <Layers size={14} className="text-zinc-500" />
                                Similarity Threshold
                            </label>
                            <div className="flex items-center gap-4">
                                <input
                                    type="range"
                                    min={0} max={1} step={0.05}
                                    value={config.similarityThreshold}
                                    onChange={(e) => handleChange('similarityThreshold', parseFloat(e.target.value))}
                                    className="flex-1 accent-indigo-500"
                                />
                                <span className="text-sm font-mono text-zinc-400 w-10 text-right">{config.similarityThreshold.toFixed(2)}</span>
                            </div>
                            <p className="text-xs text-zinc-600">Minimum score to consider relevant.</p>
                        </div>
                    </div>

                    <div className="flex items-center justify-between p-3 bg-white/5 rounded-xl border border-white/5 hover:border-indigo-500/30 transition-colors">
                        <div className="flex flex-col">
                            <span className="text-sm font-medium text-white">Hybrid Search</span>
                            <span className="text-xs text-zinc-500">Combine Keyword + Vector search</span>
                        </div>
                        <div
                            className={clsx("w-10 h-5 rounded-full relative cursor-pointer transition-colors", config.useHybridSearch ? "bg-indigo-600" : "bg-zinc-700")}
                            onClick={() => handleChange('useHybridSearch', !config.useHybridSearch)}
                        >
                            <div className={clsx("absolute top-1 w-3 h-3 bg-white rounded-full transition-all shadow-sm", config.useHybridSearch ? "left-6" : "left-1")} />
                        </div>
                    </div>

                    <div className="flex items-center justify-between p-3 bg-white/5 rounded-xl border border-white/5 hover:border-indigo-500/30 transition-colors">
                        <div className="flex flex-col">
                            <span className="text-sm font-medium text-white">Query Rewriting</span>
                            <span className="text-xs text-zinc-500">Simplify queries for better matches</span>
                        </div>
                        <div
                            className={clsx("w-10 h-5 rounded-full relative cursor-pointer transition-colors", config.useQueryRewriting ? "bg-indigo-600" : "bg-zinc-700")}
                            onClick={() => handleChange('useQueryRewriting', !config.useQueryRewriting)}
                        >
                            <div className={clsx("absolute top-1 w-3 h-3 bg-white rounded-full transition-all shadow-sm", config.useQueryRewriting ? "left-6" : "left-1")} />
                        </div>
                    </div>
                </div>

                <div className="h-px bg-white/10" />

                {/* Generation Params */}
                <div className="space-y-4">
                    <h3 className="text-xs font-bold text-zinc-500 uppercase tracking-wider">Generation</h3>

                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300 flex items-center gap-2">
                            <Thermometer size={14} className="text-zinc-500" />
                            Temperature
                        </label>
                        <div className="flex items-center gap-4">
                            <input
                                type="range"
                                min={0} max={1} step={0.1}
                                value={config.temperature}
                                onChange={(e) => handleChange('temperature', parseFloat(e.target.value))}
                                className="flex-1 accent-orange-500"
                            />
                            <span className="text-sm font-mono text-zinc-400 w-10 text-right">{config.temperature.toFixed(1)}</span>
                        </div>
                        <p className="text-xs text-zinc-600">Higher = more creative, Lower = more factual.</p>
                    </div>
                </div>

                {/* Reranking - disabled if no model */}
                <div className="space-y-2 opacity-50 pointer-events-none grayscale">
                    <label className="text-sm font-medium text-zinc-300 flex items-center gap-2">
                        <Layers size={14} className="text-zinc-500" />
                        Rerank Model (Coming Soon)
                    </label>
                    <select
                        disabled
                        value={config.rerankModel}
                        onChange={(e) => handleChange('rerankModel', e.target.value)}
                        className="w-full bg-black/20 border border-white/10 rounded-lg px-3 py-2 text-zinc-500 outline-none appearance-none"
                    >
                        {availableRerankers.map(model => (
                            <option key={model} value={model}>{model}</option>
                        ))}
                    </select>
                </div>

            </div>

            {/* Footer */}
            <div className="p-4 border-t border-white/5 bg-white/5 flex gap-3">
                <button
                    onClick={loadConfig}
                    className="p-2.5 rounded-xl hover:bg-white/10 text-zinc-400 transition-colors"
                >
                    <RotateCcw size={18} />
                </button>
                <button
                    onClick={handleSave}
                    disabled={saving}
                    className="flex-1 flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-500 text-white font-medium py-2.5 rounded-xl shadow-lg shadow-indigo-500/20 transition-all active:scale-95 disabled:opacity-50"
                >
                    <Save size={18} />
                    {saving ? 'Saving...' : 'Save Configuration'}
                </button>
            </div>
        </div>
    );
}
