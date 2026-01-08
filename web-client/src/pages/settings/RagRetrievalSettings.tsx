import { useI18n } from '../../lib/i18n';
import { Layers, GitBranch, Search } from 'lucide-react';
import { clsx } from 'clsx';

interface RagConfig {
    // Rerank
    enableRerank?: boolean;
    rerankTopK?: number;
    rerankFinalK?: number;

    // Query Rewrite
    enableQueryRewrite?: boolean;
    queryRewriteStrategy?: 'hyde' | 'multi-query' | 'expansion';
    queryRewriteCount?: number;

    // Hybrid
    enableHybridSearch?: boolean;
    hybridAlpha?: number;
    hybridBM25Boost?: number;
}

interface Props {
    config: any;
    onChange: (key: string, value: any) => void;
}

export function RagRetrievalSettings({ config, onChange }: Props) {
    const { t } = useI18n();
    const c = (config || {}) as RagConfig;

    return (
        <div className="space-y-8 animate-in fade-in slide-in-from-bottom-2 duration-300">
            {/* Header */}
            <div>
                <h3 className="text-lg font-bold text-white flex items-center gap-2 mb-2">
                    <Search className="text-purple-400" />
                    {t.settings.ragRetrieval.title}
                </h3>
                <p className="text-zinc-400 text-sm">
                    {t.settings.ragRetrieval.subtitle}
                </p>
            </div>

            {/* --- RERANK --- */}
            <div className="space-y-4">
                <div className="flex items-center justify-between">
                    <h4 className="text-xs font-bold text-purple-400 uppercase tracking-wider flex items-center gap-2">
                        <Layers size={14} /> {t.settings.ragRetrieval.rerank.title}
                    </h4>
                    <label className="relative inline-flex items-center cursor-pointer">
                        <input
                            type="checkbox"
                            checked={c.enableRerank ?? false}
                            onChange={(e) => onChange('enableRerank', e.target.checked)}
                            className="sr-only peer"
                        />
                        <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-purple-600"></div>
                    </label>
                </div>
                <div className={clsx("grid grid-cols-2 gap-4 transition-all duration-300", !c.enableRerank && "opacity-50 pointer-events-none")}>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragRetrieval.rerank.topK}</label>
                        <input type="number" value={c.rerankTopK ?? 50} onChange={(e) => onChange('rerankTopK', parseInt(e.target.value))} className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-purple-500/50 outline-none" />
                    </div>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragRetrieval.rerank.finalK}</label>
                        <input type="number" value={c.rerankFinalK ?? 10} onChange={(e) => onChange('rerankFinalK', parseInt(e.target.value))} className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-purple-500/50 outline-none" />
                    </div>
                </div>
            </div>

            <div className="h-px bg-white/5" />

            {/* --- QUERY REWRITE --- */}
            <div className="space-y-4">
                <div className="flex items-center justify-between">
                    <h4 className="text-xs font-bold text-amber-400 uppercase tracking-wider flex items-center gap-2">
                        <GitBranch size={14} /> {t.settings.ragRetrieval.queryRewrite.title}
                    </h4>
                    <label className="relative inline-flex items-center cursor-pointer">
                        <input
                            type="checkbox"
                            checked={c.enableQueryRewrite ?? false}
                            onChange={(e) => onChange('enableQueryRewrite', e.target.checked)}
                            className="sr-only peer"
                        />
                        <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-amber-600"></div>
                    </label>
                </div>

                <div className={clsx("space-y-4 transition-all duration-300", !c.enableQueryRewrite && "opacity-50 pointer-events-none")}>
                    <div className="flex gap-2">
                        {['hyde', 'multi-query', 'expansion'].map((s) => (
                            <button
                                key={s}
                                onClick={() => onChange('queryRewriteStrategy', s)}
                                className={clsx(
                                    "flex-1 py-2 px-3 rounded-lg border text-[10px] font-bold uppercase tracking-wider transition-all",
                                    c.queryRewriteStrategy === s
                                        ? "bg-amber-500/20 border-amber-500 text-amber-500"
                                        : "bg-white/5 border-transparent text-zinc-500 hover:bg-white/10"
                                )}
                            >
                                {s}
                            </button>
                        ))}
                    </div>
                </div>
            </div>

            <div className="h-px bg-white/5" />

            {/* --- HYBRID SEARCH --- */}
            <div className="space-y-4">
                <div className="flex items-center justify-between">
                    <h4 className="text-xs font-bold text-cyan-400 uppercase tracking-wider flex items-center gap-2">
                        <Search size={14} /> {t.settings.ragRetrieval.hybrid.title}
                    </h4>
                    <label className="relative inline-flex items-center cursor-pointer">
                        <input
                            type="checkbox"
                            checked={c.enableHybridSearch ?? false}
                            onChange={(e) => onChange('enableHybridSearch', e.target.checked)}
                            className="sr-only peer"
                        />
                        <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-cyan-600"></div>
                    </label>
                </div>
                <div className={clsx("grid grid-cols-2 gap-4 transition-all duration-300", !c.enableHybridSearch && "opacity-50 pointer-events-none")}>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragRetrieval.hybrid.alpha} ({c.hybridAlpha?.toFixed(1)})</label>
                        <input type="range" min={0} max={1} step={0.1} value={c.hybridAlpha ?? 0.6} onChange={e => onChange('hybridAlpha', parseFloat(e.target.value))} className="w-full accent-cyan-500" />
                    </div>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragRetrieval.hybrid.bm25Boost} ({c.hybridBM25Boost?.toFixed(1)})</label>
                        <input type="range" min={0.5} max={3} step={0.1} value={c.hybridBM25Boost ?? 1.0} onChange={e => onChange('hybridBM25Boost', parseFloat(e.target.value))} className="w-full accent-cyan-500" />
                    </div>
                </div>
            </div>
        </div>
    );
}
