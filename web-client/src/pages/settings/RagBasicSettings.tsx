import { useI18n } from '../../lib/i18n';
import { Settings2, Scissors, Activity } from 'lucide-react';

interface RagConfig {
    // Basic / Chunking
    docChunkSize?: number;
    memoryChunkSize?: number;
    chunkOverlap?: number;
    contextWindow?: number;
    summaryThreshold?: number;

    // Observability
    showRetrievalProgress?: boolean;
    showRetrievalDetails?: boolean;
    trackRetrievalMetrics?: boolean;
    autoCleanup?: boolean;
}

interface Props {
    config: any;
    onChange: (key: string, value: any) => void;
}

export function RagBasicSettings({ config, onChange }: Props) {
    const { t } = useI18n();

    // Helper to get safe values
    const c = (config || {}) as RagConfig;

    return (
        <div className="space-y-8 animate-in fade-in slide-in-from-bottom-2 duration-300">
            {/* Header */}
            <div>
                <h3 className="text-lg font-bold text-white flex items-center gap-2 mb-2">
                    <Settings2 className="text-pink-400" />
                    {t.settings.ragBasic.title}
                </h3>
                <p className="text-zinc-400 text-sm">
                    Configure how documents are processed and monitored.
                </p>
            </div>

            {/* Chunking */}
            <div className="space-y-4">
                <h4 className="text-xs font-bold text-pink-400 uppercase tracking-wider flex items-center gap-2">
                    <Scissors size={14} /> {t.settings.ragBasic.chunking.title}
                </h4>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.chunking.docSize}</label>
                        <input
                            type="number"
                            value={c.docChunkSize || 800}
                            onChange={e => onChange('docChunkSize', parseInt(e.target.value))}
                            className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-pink-500/50 outline-none transition-colors"
                        />
                    </div>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.chunking.overlap}</label>
                        <input
                            type="number"
                            value={c.chunkOverlap || 100}
                            onChange={e => onChange('chunkOverlap', parseInt(e.target.value))}
                            className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-pink-500/50 outline-none transition-colors"
                        />
                    </div>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.chunking.window}</label>
                        <input
                            type="number"
                            value={c.contextWindow || 20}
                            onChange={e => onChange('contextWindow', parseInt(e.target.value))}
                            className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-pink-500/50 outline-none transition-colors"
                        />
                    </div>
                    <div className="space-y-2">
                        <label className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.chunking.threshold}</label>
                        <input
                            type="number"
                            value={c.summaryThreshold || 10}
                            onChange={e => onChange('summaryThreshold', parseInt(e.target.value))}
                            className="w-full bg-black/20 border border-white/10 rounded-xl px-3 py-2 text-white text-sm focus:border-pink-500/50 outline-none transition-colors"
                        />
                    </div>
                </div>
            </div>

            <div className="h-px bg-white/5" />

            {/* Observability */}
            <div className="space-y-4">
                <h4 className="text-xs font-bold text-emerald-400 uppercase tracking-wider flex items-center gap-2">
                    <Activity size={14} /> {t.settings.ragBasic.observability.title}
                </h4>
                <div className="grid grid-cols-1 gap-3">
                    <label className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <input type="checkbox" checked={c.showRetrievalProgress ?? false} onChange={(e) => onChange('showRetrievalProgress', e.target.checked)} className="rounded border-zinc-600 bg-zinc-800 text-emerald-500 focus:ring-0" />
                        <span className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.observability.progress}</span>
                    </label>
                    <label className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <input type="checkbox" checked={c.showRetrievalDetails ?? false} onChange={(e) => onChange('showRetrievalDetails', e.target.checked)} className="rounded border-zinc-600 bg-zinc-800 text-emerald-500 focus:ring-0" />
                        <span className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.observability.details}</span>
                    </label>
                    <label className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <input type="checkbox" checked={c.trackRetrievalMetrics ?? false} onChange={(e) => onChange('trackRetrievalMetrics', e.target.checked)} className="rounded border-zinc-600 bg-zinc-800 text-emerald-500 focus:ring-0" />
                        <span className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.observability.metrics}</span>
                    </label>
                    <label className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <input type="checkbox" checked={c.autoCleanup ?? false} onChange={(e) => onChange('autoCleanup', e.target.checked)} className="rounded border-zinc-600 bg-zinc-800 text-emerald-500 focus:ring-0" />
                        <span className="text-sm font-medium text-zinc-300">{t.settings.ragBasic.observability.cleanup}</span>
                    </label>
                </div>
            </div>
        </div>
    );
}
