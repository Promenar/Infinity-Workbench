import { useI18n } from '../../lib/i18n';
import { Globe } from 'lucide-react';
import { GlassCard } from '../../components/ui/glass-card';

export const GeneralSection = () => {
    const { t, language, setLanguage } = useI18n();

    return (
        <div className="space-y-6">
            <GlassCard className="p-6">
                <div className="flex items-center gap-3 mb-6">
                    <div className="p-2 bg-pink-500/10 rounded-lg text-pink-400">
                        <Globe size={24} />
                    </div>
                    <h2 className="text-xl font-bold text-white">{t.settings.language}</h2>
                </div>

                <div className="flex items-center gap-4">
                    <button
                        onClick={() => setLanguage('en')}
                        className={`px-4 py-2 rounded-lg text-sm font-medium border transition-all ${language === 'en'
                            ? 'bg-pink-500/20 text-pink-400 border-pink-500/30'
                            : 'bg-white/5 text-zinc-400 border-transparent hover:bg-white/10'
                            }`}
                    >
                        English
                    </button>
                    <button
                        onClick={() => setLanguage('zh')}
                        className={`px-4 py-2 rounded-lg text-sm font-medium border transition-all ${language === 'zh'
                            ? 'bg-pink-500/20 text-pink-400 border-pink-500/30'
                            : 'bg-white/5 text-zinc-400 border-transparent hover:bg-white/10'
                            }`}
                    >
                        中文
                    </button>
                </div>
            </GlassCard>

            {/* Theme Section (Placeholder as ThemeProvider might be missing or different in Web) */}
            {/* 
            <GlassCard className="p-6">
                <div className="flex items-center gap-3 mb-6">
                    <div className="p-2 bg-purple-500/10 rounded-lg text-purple-400">
                        <Sun size={24} />
                    </div>
                    <h2 className="text-xl font-bold text-white">{t.settings.theme}</h2>
                </div>
                <div className="text-zinc-500 text-sm">
                    Dark mode is currently enforced by the Glassmorphism design.
                </div>
            </GlassCard> 
            */}
        </div>
    );
};
