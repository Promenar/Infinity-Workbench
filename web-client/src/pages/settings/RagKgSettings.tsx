import { useI18n } from '../../lib/i18n';
import { Network, DollarSign, Cpu, FileText } from 'lucide-react';
import { clsx } from 'clsx';
import { useNavigate } from 'react-router-dom';

interface RagConfig {
    enableKnowledgeGraph?: boolean;
    costStrategy?: 'summary-first' | 'on-demand' | 'full';
    enableIncrementalHash?: boolean;
    enableLocalPreprocess?: boolean;
    kgExtractionPrompt?: string;
}

interface Props {
    config: any;
    onChange: (key: string, value: any) => void;
}

export function RagKgSettings({ config, onChange }: Props) {
    const { t } = useI18n();
    const navigate = useNavigate();
    const c = (config || {}) as RagConfig;

    return (
        <div className="space-y-8 animate-in fade-in slide-in-from-bottom-2 duration-300">
            {/* Header */}
            <div className="flex items-start justify-between">
                <div>
                    <h3 className="text-lg font-bold text-white flex items-center gap-2 mb-2">
                        <Network className="text-indigo-400" />
                        {t.settings.ragKg.title}
                    </h3>
                    <p className="text-zinc-400 text-sm max-w-lg">
                        {t.settings.ragKg.subtitle}
                    </p>
                </div>

                <button
                    onClick={() => navigate('/graph')}
                    className="px-4 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 text-indigo-300 border border-indigo-500/30 rounded-lg text-sm font-medium transition-colors flex items-center gap-2"
                >
                    <Network size={16} />
                    View Graph
                </button>
            </div>

            {/* KG Toggle */}
            <div className="flex items-center justify-between p-4 bg-white/5 border border-white/5 rounded-xl">
                <span className="text-sm font-bold text-white">Enable Extraction</span>
                <label className="relative inline-flex items-center cursor-pointer">
                    <input
                        type="checkbox"
                        checked={c.enableKnowledgeGraph ?? false}
                        onChange={(e) => onChange('enableKnowledgeGraph', e.target.checked)}
                        className="sr-only peer"
                    />
                    <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-indigo-600"></div>
                </label>
            </div>

            <div className={clsx("space-y-8 transition-all duration-300", !c.enableKnowledgeGraph && "opacity-50 pointer-events-none")}>
                {/* Cost Strategy */}
                <div className="space-y-3">
                    <h4 className="text-xs font-bold text-zinc-400 uppercase flex items-center gap-2">
                        <DollarSign size={14} /> {t.settings.ragKg.strategy}
                    </h4>
                    <div className="grid grid-cols-1 gap-2">
                        {[
                            { id: 'summary-first', ...t.settings.ragKg.strategies.summary },
                            { id: 'on-demand', ...t.settings.ragKg.strategies.onDemand },
                            { id: 'full', ...t.settings.ragKg.strategies.full },
                        ].map(opt => (
                            <button
                                key={opt.id}
                                onClick={() => onChange('costStrategy', opt.id)}
                                className={clsx(
                                    "text-left p-3 rounded-xl border transition-all",
                                    c.costStrategy === opt.id
                                        ? "bg-indigo-500/10 border-indigo-500/50"
                                        : "bg-white/5 border-transparent hover:bg-white/10"
                                )}
                            >
                                <div className="text-sm font-bold text-white">{opt.label}</div>
                                <div className="text-xs text-zinc-500">{opt.desc}</div>
                            </button>
                        ))}
                    </div>
                </div>

                {/* Local Optimizations */}
                <div className="space-y-3">
                    <h4 className="text-xs font-bold text-zinc-400 uppercase flex items-center gap-2">
                        <Cpu size={14} /> {t.settings.ragKg.optimization}
                    </h4>
                    <label className="flex items-center justify-between p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <span className="text-sm text-zinc-300">{t.settings.ragKg.incremental}</span>
                        <input
                            type="checkbox"
                            checked={c.enableIncrementalHash ?? false}
                            onChange={(e) => onChange('enableIncrementalHash', e.target.checked)}
                            className="rounded border-zinc-600 bg-zinc-800 text-indigo-500 focus:ring-0"
                        />
                    </label>
                    <label className="flex items-center justify-between p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <span className="text-sm text-zinc-300">{t.settings.ragKg.local}</span>
                        <input
                            type="checkbox"
                            checked={c.enableLocalPreprocess ?? false}
                            onChange={(e) => onChange('enableLocalPreprocess', e.target.checked)}
                            className="rounded border-zinc-600 bg-zinc-800 text-indigo-500 focus:ring-0"
                        />
                    </label>
                </div>

                {/* Prompt */}
                <div className="space-y-2">
                    <h4 className="text-xs font-bold text-zinc-400 uppercase flex items-center gap-2">
                        <FileText size={14} /> {t.settings.ragKg.prompt}
                    </h4>
                    <textarea
                        value={c.kgExtractionPrompt || ''}
                        onChange={(e) => onChange('kgExtractionPrompt', e.target.value)}
                        className="w-full h-32 bg-black/30 border border-white/10 rounded-xl p-3 text-xs font-mono text-zinc-300 outline-none focus:border-indigo-500 resize-none placeholder:text-zinc-600"
                        placeholder="Enter system prompt for KG extraction..."
                    />
                </div>
            </div>
        </div>
    );
}
