import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { Sparkles, ArrowRight, Zap, Bot } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { GlassCard } from '../components/ui/glass-card';
import { storeService, type Assistant } from '../services/StoreService';
import { useI18n } from '../lib/i18n';

export function HomePage() {
    const navigate = useNavigate();
    const { t } = useI18n();
    const [assistants, setAssistants] = useState<Assistant[]>([]);
    const [query, setQuery] = useState('');

    useEffect(() => {
        setAssistants(storeService.getAssistants());
        const update = (list: any) => setAssistants(list);
        storeService.on('assistants_updated', update);
        return () => { storeService.off('assistants_updated', update); };
    }, []);

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        if (!query.trim()) return;
        // Default to Super Assistant for quick queries from Home
        navigate(`/chat/super_assistant?q=${encodeURIComponent(query)}`);
    };

    return (
        <div className="flex-1 w-full h-full overflow-y-auto custom-scrollbar p-8 md:p-12 relative">

            {/* Ambient Background Glows */}
            <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[800px] h-[500px] bg-indigo-500/10 blur-[120px] rounded-full pointer-events-none" />

            <div className="max-w-5xl mx-auto space-y-16 relative z-10">

                {/* 1. Hero Section */}
                <div className="space-y-8 text-center pt-12">
                    <motion.div
                        initial={{ opacity: 0, y: 20 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.6 }}
                        className="space-y-4"
                    >
                        <h1 className="text-5xl md:text-7xl font-bold tracking-tight text-white drop-shadow-2xl">
                            <span className="text-transparent bg-clip-text bg-linear-to-b from-white to-white/60">
                                {t.home.heroTitle1}
                            </span>
                            <br />
                            <span className="text-transparent bg-clip-text bg-linear-to-r from-indigo-400 via-purple-400 to-indigo-400 animate-gradient-x">
                                {t.home.heroTitle2}
                            </span>
                        </h1>
                        <p className="text-lg text-zinc-400 max-w-2xl mx-auto">
                            {t.home.heroSubtitle}
                        </p>
                    </motion.div>

                    {/* Hero Input (Aceternity Style) */}
                    <motion.div
                        initial={{ opacity: 0, scale: 0.95 }}
                        animate={{ opacity: 1, scale: 1 }}
                        transition={{ delay: 0.2, duration: 0.5 }}
                        className="max-w-2xl mx-auto relative group"
                    >
                        <div className="absolute -inset-1 bg-linear-to-r from-indigo-500 to-purple-600 rounded-2xl blur opacity-25 group-hover:opacity-50 transition duration-500"></div>
                        <form onSubmit={handleSearch} className="relative bg-black/80 backdrop-blur-xl border border-white/10 rounded-2xl flex items-center p-2 shadow-2xl">
                            <div className="pl-4 text-zinc-400">
                                <Sparkles size={24} />
                            </div>
                            <input
                                type="text"
                                value={query}
                                onChange={e => setQuery(e.target.value)}
                                placeholder={t.home.inputPlaceholder}
                                className="flex-1 bg-transparent border-none outline-none text-lg text-white px-4 py-3 placeholder:text-zinc-600"
                                autoFocus
                            />
                            <button type="submit" className="bg-indigo-600 hover:bg-indigo-500 text-white p-3 rounded-xl transition-colors">
                                <ArrowRight size={20} />
                            </button>
                        </form>
                    </motion.div>
                </div>

                {/* 2. Agent Grid (Plaza) */}
                <div className="space-y-6">
                    <div className="flex items-center justify-between px-2">
                        <h2 className="text-xl font-semibold text-white flex items-center gap-2">
                            <Zap className="text-yellow-400" size={20} />
                            {t.home.activeAgents}
                        </h2>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {assistants.filter(a => a.id !== 'super_assistant').map((agent, i) => (
                            <motion.div
                                key={agent.id}
                                initial={{ opacity: 0, y: 20 }}
                                animate={{ opacity: 1, y: 0 }}
                                transition={{ delay: 0.1 * i }}
                            >
                                <GlassCard
                                    className="p-6 cursor-pointer h-full"
                                    enableTilt
                                    spotlightColor={agent.color ? agent.color + '40' : undefined}
                                    onClick={() => navigate(`/chat/${agent.id}`)}
                                >
                                    <div className="flex items-start justify-between mb-4">
                                        <div
                                            className="w-12 h-12 rounded-2xl flex items-center justify-center text-white text-xl shadow-lg border border-white/5"
                                            style={{ backgroundColor: agent.color || '#6366f1' }}
                                        >
                                            <Bot size={24} />
                                        </div>
                                        <div className="px-2.5 py-1 rounded-full bg-white/5 border border-white/5 text-xs font-medium text-zinc-400">
                                            {agent.defaultModel}
                                        </div>
                                    </div>
                                    <h3 className="text-xl font-bold text-white mb-2">{agent.name}</h3>
                                    <p className="text-zinc-400 text-sm line-clamp-2 leading-relaxed">
                                        {agent.description || "A helpful AI assistant ready to assist you using custom knowledge and tools."}
                                    </p>

                                    <div className="mt-6 flex items-center gap-2 text-indigo-400 text-sm font-medium opacity-0 group-hover:opacity-100 transition-opacity">
                                        <span>{t.home.startSession}</span>
                                        <ArrowRight size={16} />
                                    </div>
                                </GlassCard>
                            </motion.div>
                        ))}

                        {/* Create New Card */}
                        <motion.button
                            initial={{ opacity: 0, y: 20 }}
                            animate={{ opacity: 1, y: 0 }}
                            transition={{ delay: 0.1 * (assistants.length + 1) }}
                            className="group h-full min-h-[200px] rounded-2xl border border-dashed border-white/10 hover:border-white/30 bg-white/5 hover:bg-white/10 transition-all flex flex-col items-center justify-center gap-4"
                            onClick={() => navigate('/assistants')} // Manage/Create
                        >
                            <div className="w-12 h-12 rounded-full bg-white/5 flex items-center justify-center group-hover:scale-110 transition-transform">
                                <Sparkles className="text-zinc-500 group-hover:text-white" size={24} />
                            </div>
                            <span className="text-zinc-500 group-hover:text-white font-medium">{t.home.createAgent}</span>
                        </motion.button>
                    </div>
                </div>

            </div>
        </div>
    );
}
