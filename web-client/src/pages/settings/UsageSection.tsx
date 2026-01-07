import React, { useEffect, useState } from 'react';
import { GlassCard } from '../../components/ui/glass-card';
import { useI18n } from '../../lib/i18n';
import { BarChart3, Database, MessageSquare, Zap, RotateCcw } from 'lucide-react';
import { workbenchClient } from '../../services/WorkbenchClient';

export const UsageSection: React.FC = () => {
    const { t } = useI18n();
    const [stats, setStats] = useState<any>(null);


    useEffect(() => {
        loadStats();
    }, []);

    const loadStats = async () => {
        try {
            const data = await workbenchClient.getStats();
            setStats(data);
        } catch (e) {
            console.error('Failed to load stats', e);
        }
    };

    const handleReset = async () => {
        if (!confirm(t.common.confirm)) return;
        try {
            await workbenchClient.resetStats();
            await loadStats();
        } catch (e) {
            console.error('Failed to reset stats', e);
        }
    };

    // Defaults if no stats provided
    const total = stats?.globalTotal?.total || 0;
    const input = stats?.globalTotal?.chatInput?.count || 0;
    const output = stats?.globalTotal?.chatOutput?.count || 0;
    const rag = stats?.globalTotal?.ragSystem?.count || 0;

    // Calc percentages
    const max = Math.max(1, total);
    const inputPct = (input / max) * 100;
    const outputPct = (output / max) * 100;
    const ragPct = (rag / max) * 100;

    return (
        <div className="space-y-6">
            <div className="flex items-center gap-3">
                <div className="p-2 bg-amber-500/10 rounded-lg text-amber-400">
                    <BarChart3 size={24} />
                </div>
                <div>
                    <h2 className="text-xl font-bold text-white">{t.settings.usage.title}</h2>
                    <p className="text-sm text-zinc-400">{t.settings.usage.subtitle}</p>
                </div>
            </div>

            <GlassCard className="p-8">
                {/* Total Circle */}
                <div className="flex justify-center mb-10">
                    <div className="relative w-48 h-48 rounded-full border-4 border-dashed border-white/10 flex items-center justify-center flex-col animate-spin-slow">
                        <div className="absolute inset-0 rounded-full border-4 border-indigo-500/20 border-t-indigo-500 rotate-45 transform" />
                        <div className="text-4xl font-black text-white tracking-tighter z-10 animate-none transform-none">
                            {total.toLocaleString()}
                        </div>
                        <div className="text-xs font-bold text-zinc-500 uppercase tracking-widest mt-2">
                            {t.settings.usage.totalToken}
                        </div>
                    </div>
                </div>

                {/* Bars */}
                <div className="space-y-6 max-w-xl mx-auto">
                    {/* Input */}
                    <div>
                        <div className="flex justify-between text-sm mb-2">
                            <span className="flex items-center gap-2 text-violet-400 font-bold">
                                <MessageSquare size={14} />
                                {t.settings.usage.prompt}
                            </span>
                            <span className="font-mono text-white">{input.toLocaleString()}</span>
                        </div>
                        <div className="h-2 bg-white/5 rounded-full overflow-hidden">
                            <div className="h-full bg-violet-500 rounded-full" style={{ width: `${inputPct}%` }} />
                        </div>
                    </div>

                    {/* Output */}
                    <div>
                        <div className="flex justify-between text-sm mb-2">
                            <span className="flex items-center gap-2 text-amber-400 font-bold">
                                <Zap size={14} />
                                {t.settings.usage.completion}
                            </span>
                            <span className="font-mono text-white">{output.toLocaleString()}</span>
                        </div>
                        <div className="h-2 bg-white/5 rounded-full overflow-hidden">
                            <div className="h-full bg-amber-500 rounded-full" style={{ width: `${outputPct}%` }} />
                        </div>
                    </div>

                    {/* RAG */}
                    <div>
                        <div className="flex justify-between text-sm mb-2">
                            <span className="flex items-center gap-2 text-emerald-400 font-bold">
                                <Database size={14} />
                                {t.settings.usage.rag}
                            </span>
                            <span className="font-mono text-white">{rag.toLocaleString()}</span>
                        </div>
                        <div className="h-2 bg-white/5 rounded-full overflow-hidden">
                            <div className="h-full bg-emerald-500 rounded-full" style={{ width: `${ragPct}%` }} />
                        </div>
                    </div>
                </div>

                {/* Reset Action */}
                <div className="mt-8 pt-6 border-t border-white/5 flex justify-center">
                    <button
                        onClick={handleReset}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors text-xs font-bold uppercase tracking-wider hover:scale-105 active:scale-95"
                    >
                        <RotateCcw size={14} />
                        {t.settings.usage.reset}
                    </button>
                </div>
            </GlassCard>
        </div>
    );
};
