import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { workbenchClient } from '../services/WorkbenchClient';
import { Send, Image as ImageIcon, StopCircle, ArrowLeft, Bot, Sparkles, Globe, BrainCircuit, ChevronDown } from 'lucide-react';
import { MessageBubble } from '../components/MessageBubble';
import clsx from 'clsx';
import type { ChatMessage, StreamMessage } from '../types/chat';
import { useI18n } from '../lib/i18n';

export function ChatPage() {
    const { sessionId } = useParams<{ sessionId: string }>();
    const navigate = useNavigate();
    const { t } = useI18n();

    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState('');
    const [loading, setLoading] = useState(true);
    const [generating, setGenerating] = useState(false);
    const [sessionTitle, setSessionTitle] = useState('');

    // Status Indicators
    const [status, setStatus] = useState<'idle' | 'thinking' | 'searching' | 'generating'>('idle');

    // Controls
    // Controls - Persist with localStorage
    const [ragEnabled, setRagEnabled] = useState(() => localStorage.getItem('chat_ragEnabled') !== 'false');
    const [webSearchEnabled, setWebSearchEnabled] = useState(() => localStorage.getItem('chat_webSearchEnabled') !== 'false');
    const [reasoningEnabled, setReasoningEnabled] = useState(() => localStorage.getItem('chat_reasoningEnabled') !== 'false');

    // Persistence Effects
    useEffect(() => { localStorage.setItem('chat_ragEnabled', String(ragEnabled)); }, [ragEnabled]);
    useEffect(() => { localStorage.setItem('chat_webSearchEnabled', String(webSearchEnabled)); }, [webSearchEnabled]);
    useEffect(() => { localStorage.setItem('chat_reasoningEnabled', String(reasoningEnabled)); }, [reasoningEnabled]);

    // Models
    const [selectedModel, setSelectedModel] = useState<string>('');
    const [availableModels, setAvailableModels] = useState<{ id: string, name: string }[]>([]);
    const [showModelMenu, setShowModelMenu] = useState(false);

    // Stats
    const [tokenStats, setTokenStats] = useState({ input: 0, output: 0, total: 0 });

    const scrollRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        loadConfig();
        if (sessionId) loadHistory();
        // ... (rest of useEffect)
        const onMessage = (msg: any) => {
            if (msg.type === 'MSG_STREAM_UPDATE') {
                const payload = msg.payload as StreamMessage;
                if (payload.sessionId !== sessionId) return;

                setStatus('generating');
                setGenerating(true);
                handleStreamUpdate(payload);
            }
            else if (msg.type === 'MSG_STREAM_COMPLETE') {
                if (msg.payload.sessionId === sessionId) {
                    setGenerating(false);
                    setStatus('idle');
                    // Mock token update for now, ideally backend sends this
                    setTokenStats(prev => ({
                        input: prev.input + input.length / 4,
                        output: prev.output + (msg.payload.content?.length || 0) / 4,
                        total: prev.total // Update logic would go here
                    }));
                    loadHistory();
                }
            }
            else if (msg.type === 'MSG_THINKING_START') {
                setStatus('thinking');
            }
            else if (msg.type === 'MSG_SEARCH_START') {
                setStatus('searching');
            }
        };

        workbenchClient.on('message', onMessage);
        return () => { workbenchClient.off('message', onMessage); };
    }, [sessionId]);

    const loadConfig = async () => {
        try {
            const config = await workbenchClient.getConfig();
            console.log("Loaded config:", config); // DEBUG
            if (config.providers) {
                const models = config.providers.flatMap((p: any) =>
                    (p.models || [])
                        .filter((m: any) => {
                            if (m.enabled === false) return false;
                            if (p.enabled === false) return false;
                            return true;
                        })
                        .map((m: any) => ({ id: m.id, name: m.name || m.id }))
                );
                console.log("Filtered available models:", models); // DEBUG
                setAvailableModels(models);
                // Set default if not set
                if (models.length > 0 && !selectedModel) {
                    // Check if session has a model, else default
                    setSelectedModel(models[0].id);
                }
            }
        } catch (e) { console.error("Failed to load models", e); }
    };

    // Auto-scroll
    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages, generating, status]);

    const loadHistory = async () => {
        if (!sessionId) return;
        try {
            const session = await workbenchClient.request('CMD_GET_HISTORY', { id: sessionId });
            if (session) {
                if (session.messages) setMessages(session.messages);
                if (session.title) setSessionTitle(session.title);
                // If session stores model, set it here
            }
        } catch (e) {
            console.error('Failed to load history', e);
        } finally {
            setLoading(false);
        }
    };

    const handleStreamUpdate = (payload: StreamMessage) => {
        setMessages(prev => {
            const existing = prev.find(m => m.id === payload.messageId);
            if (existing) {
                return prev.map(m => m.id === payload.messageId ? { ...m, content: payload.content } : m);
            } else {
                return [...prev, {
                    id: payload.messageId,
                    role: 'assistant',
                    content: payload.content,
                    createdAt: Date.now()
                }];
            }
        });
    };

    const handleSend = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!input.trim() || !sessionId) return;

        const content = input;
        setInput('');
        setGenerating(true);
        setStatus('thinking'); // Optimistic status

        // Optimistically add user message
        const userMsg: ChatMessage = {
            id: 'temp_' + Date.now(),
            role: 'user',
            content,
            createdAt: Date.now()
        };
        setMessages(prev => [...prev, userMsg]);

        try {
            await workbenchClient.sendMessage(sessionId, content, {
                model: selectedModel,
                ragOptions: {
                    enableDocs: ragEnabled,
                    enableMemory: ragEnabled
                },
                webSearch: webSearchEnabled,
                reasoning: reasoningEnabled
            });
        } catch (err) {
            console.error('Send failed', err);
            setGenerating(false);
            setStatus('idle');
        }
    };

    const handleAbort = async () => {
        if (!sessionId) return;
        try {
            await workbenchClient.abortGeneration(sessionId);
            setGenerating(false);
            setStatus('idle');
        } catch (e) {
            console.error('Abort failed', e);
        }
    };

    if (loading) return (
        <div className="flex items-center justify-center h-full bg-[#09090b]">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
        </div>
    );

    return (
        <div className="flex flex-col h-screen bg-[#09090b] relative overflow-hidden font-sans">
            {/* Ambient Background */}
            <div className="absolute top-0 left-0 w-full h-[500px] bg-indigo-900/10 blur-[100px] pointer-events-none" />

            {/* Header */}
            <div className="flex flex-col border-b border-white/5 bg-[#09090b]/80 backdrop-blur-md z-20">
                <div className="flex items-center justify-between px-6 py-3">
                    <div className="flex items-center gap-4">
                        <button
                            onClick={() => navigate('/sessions')}
                            className="p-2 -ml-2 hover:bg-white/10 rounded-full transition-colors md:hidden text-zinc-400 hover:text-white"
                        >
                            <ArrowLeft size={20} />
                        </button>
                        <div>
                            <div className="flex items-center gap-2">
                                <h1 className="font-bold text-white tracking-tight">{sessionTitle || t.chat.newChat}</h1>
                                {status !== 'idle' && (
                                    <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-indigo-500/10 border border-indigo-500/20 text-[10px] font-bold text-indigo-400 uppercase tracking-wider animate-pulse">
                                        {status === 'thinking' && <BrainCircuit size={10} />}
                                        {status === 'searching' && <Globe size={10} />}
                                        {status === 'generating' && <Sparkles size={10} />}
                                        {t.status[status]}
                                    </span>
                                )}
                            </div>
                            <p className="text-xs text-zinc-500 font-mono flex items-center gap-2">
                                <span>{sessionId?.slice(0, 8)}...</span>
                                {tokenStats.output > 0 && <span className="opacity-50">• {Math.round(tokenStats.output)} tokens</span>}
                            </p>
                        </div>
                    </div>

                    <div className="flex items-center gap-3">
                        {/* Model Selector */}
                        <div className="relative">
                            <button
                                onClick={() => setShowModelMenu(!showModelMenu)}
                                className="flex items-center gap-2 px-3 py-1.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-lg text-xs font-medium text-zinc-300 transition-all min-w-[140px] justify-between"
                            >
                                <span className="truncate max-w-[120px]">
                                    {availableModels.find(m => m.id === selectedModel)?.name || selectedModel || t.chat.modelSelector}
                                </span>
                                <ChevronDown size={14} className="opacity-50" />
                            </button>

                            {showModelMenu && (
                                <>
                                    <div className="fixed inset-0 z-10" onClick={() => setShowModelMenu(false)} />
                                    <div className="absolute top-full right-0 mt-2 w-64 max-h-64 overflow-y-auto bg-[#18181b] border border-white/10 rounded-xl shadow-2xl z-20 custom-scrollbar p-1">
                                        {availableModels.map(m => (
                                            <button
                                                key={m.id}
                                                onClick={() => { setSelectedModel(m.id); setShowModelMenu(false); }}
                                                className={clsx(
                                                    "w-full text-left px-3 py-2 rounded-lg text-xs font-medium transition-colors",
                                                    selectedModel === m.id ? "bg-indigo-600 text-white" : "text-zinc-400 hover:text-zinc-200 hover:bg-white/5"
                                                )}
                                            >
                                                {m.name}
                                            </button>
                                        ))}
                                    </div>
                                </>
                            )}
                        </div>
                    </div>
                </div>

                {/* Sub-Header Controls */}
                <div className="flex items-center gap-2 px-6 pb-3 overflow-x-auto no-scrollbar">
                    <button
                        onClick={() => setRagEnabled(!ragEnabled)}
                        title={t.chat.ragTooltip}
                        className={clsx(
                            "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0",
                            ragEnabled
                                ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20 shadow-[0_0_10px_rgba(16,185,129,0.1)]"
                                : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10"
                        )}
                    >
                        <Bot size={12} />
                        {t.chat.rag}
                    </button>

                    <button
                        onClick={() => setWebSearchEnabled(!webSearchEnabled)}
                        title={t.chat.searchTooltip}
                        className={clsx(
                            "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0",
                            webSearchEnabled
                                ? "bg-cyan-500/10 text-cyan-400 border-cyan-500/20 shadow-[0_0_10px_rgba(6,182,212,0.1)]"
                                : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10"
                        )}
                    >
                        <Globe size={12} />
                        {t.chat.search}
                    </button>

                    <button
                        onClick={() => setReasoningEnabled(!reasoningEnabled)}
                        title={t.chat.thinkingTooltip}
                        className={clsx(
                            "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0",
                            reasoningEnabled
                                ? "bg-violet-500/10 text-violet-400 border-violet-500/20 shadow-[0_0_10px_rgba(139,92,246,0.1)]"
                                : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10"
                        )}
                    >
                        <BrainCircuit size={12} />
                        {t.chat.thinking}
                    </button>

                    <div className="h-4 w-px bg-white/10 mx-1" />

                    <span className="text-[10px] text-zinc-600 font-mono">
                        TEMP: {0.7}
                    </span>
                </div>
            </div>

            {/* Messages Area */}
            <div
                ref={scrollRef}
                className="flex-1 overflow-y-auto p-4 md:p-6 scroll-smooth z-0 custom-scrollbar"
            >
                <div className="max-w-5xl mx-auto space-y-6 pb-4">
                    {messages.length === 0 ? (
                        <div className="flex flex-col items-center justify-center py-20 opacity-50 space-y-4">
                            <div className="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center">
                                <Bot size={32} className="text-zinc-500" />
                            </div>
                            <p className="text-zinc-500">Pick a model and start creating</p>
                        </div>
                    ) : (
                        messages.map(msg => (
                            <MessageBubble
                                key={msg.id}
                                message={msg}
                                isStreaming={msg.role === 'assistant' && generating && msg.id === messages[messages.length - 1].id}
                            />
                        ))
                    )}
                    {/* Invisible padding for bottom scroll */}
                    <div className="h-10" />
                </div>
            </div>

            {/* Input Area */}
            <div className="p-4 md:p-6 bg-linear-to-t from-[#09090b] via-[#09090b] to-transparent z-10">
                <div className="max-w-5xl mx-auto relative group">
                    {/* Glowing border effect */}
                    <div className="absolute -inset-0.5 bg-linear-to-r from-indigo-500 to-purple-500 rounded-2xl opacity-0 group-focus-within:opacity-30 transition-opacity blur duration-700 pointer-events-none" />

                    <form onSubmit={handleSend} className="relative flex items-center gap-2 bg-[#18181b] p-2 rounded-2xl border border-white/10 shadow-2xl focus-within:border-white/20 transition-colors">

                        <button type="button" className="p-2.5 text-zinc-400 hover:text-white hover:bg-white/5 rounded-xl transition-all">
                            <ImageIcon size={20} />
                        </button>

                        <textarea
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter' && !e.shiftKey) {
                                    e.preventDefault();
                                    handleSend(e);
                                }
                            }}
                            placeholder={status === 'idle' ? t.chat.placeholder : t.chat.generating}
                            className="flex-1 max-h-32 min-h-[44px] py-2.5 px-2 bg-transparent border-none outline-none focus:outline-none focus:ring-0 text-gray-100 placeholder-zinc-600 resize-none font-sans text-[15px] leading-relaxed"
                            rows={1}
                        />

                        {generating ? (
                            <button
                                type="button"
                                onClick={handleAbort}
                                className="p-2.5 bg-red-500/20 text-red-400 hover:bg-red-500/30 rounded-xl transition-all active:scale-95 border border-red-500/20"
                            >
                                <StopCircle size={20} className="animate-pulse" />
                            </button>
                        ) : (
                            <button
                                type="submit"
                                disabled={!input.trim()}
                                className="p-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:bg-zinc-800 disabled:text-zinc-600 text-white rounded-xl transition-all active:scale-95 shadow-lg shadow-indigo-600/20"
                            >
                                <Send size={20} />
                            </button>
                        )}
                    </form>
                    <p className="text-center text-[10px] text-zinc-700 mt-3 font-medium tracking-wide">
                        {t.chat.aiGenerated}
                    </p>
                </div>
            </div>
        </div>
    );
}
