import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { workbenchClient } from '../services/WorkbenchClient';
import { Send, Image as ImageIcon, StopCircle, Bot, Sparkles, Globe, BrainCircuit, ChevronDown, MoreVertical, Trash2, Zap, Cpu, ArrowDown } from 'lucide-react';
import { MessageBubble } from '../components/MessageBubble';
import clsx from 'clsx';
import type { ChatMessage, StreamMessage } from '../types/chat';
import { useI18n } from '../lib/i18n';
import { storeService } from '../services/StoreService';

export function ChatPage() {
    const { sessionId: routeSessionId, agentId } = useParams<{ sessionId?: string, agentId?: string }>();
    // Internal state for sessionId (might change from undefined -> created UUID)
    const sessionId = routeSessionId;

    const navigate = useNavigate();
    const { t } = useI18n();

    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState('');
    const [loading, setLoading] = useState(!!sessionId); // Only load if session exists
    const [generating, setGenerating] = useState(false);
    const [sessionTitle, setSessionTitle] = useState('');
    const [agentName, setAgentName] = useState('');

    // Status Indicators
    const [status, setStatus] = useState<'idle' | 'thinking' | 'searching' | 'generating'>('idle');

    const [ragEnabled, setRagEnabled] = useState(() => localStorage.getItem('chat_ragEnabled_v2') !== 'false');
    const [webSearchEnabled, setWebSearchEnabled] = useState(() => localStorage.getItem('chat_webSearchEnabled_v2') !== 'false');
    const [reasoningEnabled, setReasoningEnabled] = useState(() => localStorage.getItem('chat_reasoningEnabled_v2') !== 'false');

    useEffect(() => { localStorage.setItem('chat_ragEnabled_v2', String(ragEnabled)); }, [ragEnabled]);
    useEffect(() => { localStorage.setItem('chat_webSearchEnabled_v2', String(webSearchEnabled)); }, [webSearchEnabled]);
    useEffect(() => { localStorage.setItem('chat_reasoningEnabled_v2', String(reasoningEnabled)); }, [reasoningEnabled]);

    const [selectedModel, setSelectedModel] = useState<string>('');
    const [availableModels, setAvailableModels] = useState<{ id: string, name: string, providerId: string }[]>([]);
    const [showModelMenu, setShowModelMenu] = useState(false);
    const [showActionsMenu, setShowActionsMenu] = useState(false);
    const [showScrollButton, setShowScrollButton] = useState(false);

    // Helper to beautify model names
    const formatModelName = (name: string) => {
        // Remove random hash suffixes (e.g., -6zpc1h)
        return name.replace(/-[a-z0-9]{6,}$/i, '')
            .replace(/:/g, ' ')
            .replace(/-/g, ' ');
    };

    const [tokenStats, setTokenStats] = useState({ input: 0, output: 0, total: 0 });

    const scrollRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLTextAreaElement>(null);

    // Auto-resize input
    useEffect(() => {
        if (inputRef.current) {
            inputRef.current.style.height = 'auto';
            inputRef.current.style.height = Math.min(inputRef.current.scrollHeight, 128) + 'px';
        }
    }, [input]);

    // Initialize Agent Info if new session
    useEffect(() => {
        if (!sessionId && agentId) {
            const agents = storeService.getAssistants();
            const target = agents.find(a => a.id === agentId);
            if (target) {
                setAgentName(target.name);
                setSessionTitle(`Chat with ${target.name}`);
                if (target.defaultModel) setSelectedModel(target.defaultModel);
            }
        }
    }, [sessionId, agentId]);

    useEffect(() => {
        loadConfig();
        if (sessionId) loadHistory();

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
                    setTokenStats(prev => ({
                        input: prev.input + input.length / 4,
                        output: prev.output + (msg.payload.content?.length || 0) / 4,
                        total: prev.total
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
            else if (msg.type === 'MSG_MESSAGE_UPDATE') {
                const updatedMsg = msg.payload;
                setMessages(prev => prev.map(m => m.id === updatedMsg.id ? { ...m, ...updatedMsg } : m));
            }
        };

        workbenchClient.on('message', onMessage);
        return () => { workbenchClient.off('message', onMessage); };
    }, [sessionId]);

    const loadConfig = async () => {
        try {
            const config = await workbenchClient.getConfig();
            if (config.providers) {
                const models = config.providers.flatMap((p: any) =>
                    (p.models || [])
                        .filter((m: any) => p.enabled !== false && m.enabled !== false)
                        .map((m: any) => ({ id: m.id, name: m.name || m.id, providerId: p.id }))
                );
                setAvailableModels(models);
                if (models.length > 0 && !selectedModel) {
                    setSelectedModel(models[0].id);
                }
            }
        } catch (e) { console.error("Failed to load models", e); }
    };

    const isUserAtBottom = useRef(true);
    const handleScroll = () => {
        if (!scrollRef.current) return;
        const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;

        const isBottom = scrollHeight - scrollTop - clientHeight < 100;
        isUserAtBottom.current = isBottom;
        setShowScrollButton(!isBottom);
    };

    const scrollToBottom = () => {
        scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
    };

    useEffect(() => {
        if (scrollRef.current && isUserAtBottom.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages, generating, status]);

    const loadHistory = async () => {
        if (!sessionId) return;
        try {
            setLoading(true);
            const session = await workbenchClient.request('CMD_GET_HISTORY', { id: sessionId });
            if (session) {
                if (session.messages) setMessages(session.messages);
                if (session.title) setSessionTitle(session.title);
                if (session.modelId) setSelectedModel(session.modelId);
            }
        } catch (e) {
            console.error('Failed to load history', e);
        } finally {
            setLoading(false);
        }
    };

    // Estimate tokens on load if zero
    useEffect(() => {
        if (messages.length > 0 && tokenStats.total === 0) {
            let inputLen = 0;
            let outputLen = 0;
            messages.forEach(m => {
                if (m.role === 'user') inputLen += m.content.length;
                if (m.role === 'assistant') outputLen += m.content.length;
            });
            const input = Math.round(inputLen / 4);
            const output = Math.round(outputLen / 4);
            setTokenStats({ input, output, total: input + output });
        }
    }, [messages.length]); // Only recalc when message count changes substantially (loading)

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
        if (!input.trim()) return;

        let activeSessionId = sessionId;
        const content = input;
        setInput('');
        setGenerating(true);
        setStatus('thinking');

        // Optimistic UI
        const userMsg: ChatMessage = {
            id: 'temp_' + Date.now(),
            role: 'user',
            content,
            createdAt: Date.now()
        };
        setMessages(prev => [...prev, userMsg]);

        try {
            // Lazy Creation: If no session, create one now
            if (!activeSessionId) {
                if (!agentId) throw new Error("No Agent ID or Session ID");
                const newSession = await workbenchClient.request('CMD_CREATE_SESSION', { agentId });
                if (!newSession?.id) throw new Error("Failed to create session");
                activeSessionId = newSession.id;

                // Navigate but keep state/prediction active?
                // Actually, if we navigate, this component might unmount.
                // We should probably sendMessage FIRST, then navigate.
                // But sendMessage requires the sessionId which we just got.
                // If we navigate, the param changes, component remounts.
                // Remount = state reset. User sees flicker.
                // Ideally we update the URL without remounting (History API push).
                window.history.replaceState(null, '', `/chat/${activeSessionId}`);
                // But react-router might not know.
                // Let's just send the message, THEN navigate properly to sync everything.
            }

            await workbenchClient.sendMessage(activeSessionId!, content, {
                model: selectedModel,
                ragOptions: {
                    enableDocs: ragEnabled,
                    enableMemory: ragEnabled
                },
                webSearch: webSearchEnabled,
                reasoning: reasoningEnabled
            });

            // If we just created the session, fully sync the route now
            if (!sessionId && activeSessionId) {
                navigate(`/chat/${activeSessionId}`, { replace: true });
            }

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
        } catch (e) { console.error('Abort failed', e); }
    };

    const handleDeleteMessage = async (msgId: string) => {
        if (!sessionId) return;
        setMessages(prev => prev.filter(m => m.id !== msgId));
        try { await workbenchClient.deleteMessage(sessionId, msgId); }
        catch (e) { console.error('Delete message failed', e); loadHistory(); }
    };

    const handleRegenerateMessage = async (msgId: string) => {
        if (!sessionId) return;
        setGenerating(true);
        setStatus('thinking');
        try { await workbenchClient.regenerateMessage(sessionId, msgId); }
        catch (e) { console.error('Regenerate failed', e); setGenerating(false); setStatus('idle'); }
    };

    const handleDeleteSession = async () => {
        if (!sessionId || !window.confirm(t.chat.confirmDelete || 'Delete this session?')) return;
        try {
            await workbenchClient.deleteSession(sessionId);
            navigate('/chat'); // Or home
        } catch (e) {
            console.error('Delete session failed', e);
        }
    };

    if (loading) return (
        <div className="flex items-center justify-center h-full bg-[#09090b]">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
        </div>
    );

    return (
        <div className="flex flex-col h-full w-full bg-[#09090b] relative overflow-hidden font-sans">
            {/* Ambient Background - Subtle localized glow */}
            <div className="absolute top-0 right-0 w-[500px] h-[500px] bg-indigo-900/10 blur-[100px] pointer-events-none" />

            {/* Header */}
            <div className="flex flex-col border-b border-white/5 bg-[#09090b]/80 backdrop-blur-md z-20">
                <div className="flex items-center justify-between px-6 py-3">
                    <div className="flex items-center gap-4">
                        {/* Mobile back trigger if needed, but Rail handles nav */}
                        <div className="flex items-center gap-3">
                            <div className="w-10 h-10 rounded-xl bg-white/5 flex items-center justify-center border border-white/5">
                                <Bot size={20} className="text-indigo-400" />
                            </div>
                            <div>
                                <div className="flex items-center gap-2">
                                    <h1 className="font-bold text-white tracking-tight text-lg">
                                        {agentName || sessionTitle || t.chat.newChat}
                                    </h1>
                                    {status !== 'idle' && (
                                        <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-indigo-500/10 border border-indigo-500/20 text-[10px] font-bold text-indigo-400 uppercase tracking-wider animate-pulse">
                                            {status === 'thinking' && <BrainCircuit size={10} />}
                                            {status === 'searching' && <Globe size={10} />}
                                            {status === 'generating' && <Sparkles size={10} />}
                                            {t.status[status]}
                                        </span>
                                    )}
                                </div>

                                <p className="text-xs text-zinc-500 font-mono flex items-center gap-2 mt-0.5">
                                    {sessionId ? (
                                        <span className="opacity-50">{sessionId.slice(0, 8)}</span>
                                    ) : (
                                        <span className="text-indigo-400/70">New Session</span>
                                    )}
                                    {(tokenStats.total > 0) && (
                                        <>
                                            <span className="opacity-30">|</span>
                                            <span className="opacity-60" title={t.settings.usage.stats.input}>In: {tokenStats.input}</span>
                                            <span className="opacity-60" title={t.settings.usage.stats.output}>Out: {tokenStats.output}</span>
                                            <span className="text-zinc-400 font-bold" title={t.settings.usage.stats.total}>Total: {tokenStats.total}</span>
                                        </>
                                    )}
                                </p>
                            </div>
                        </div>
                    </div>

                    <div className="flex items-center gap-3">
                        <div className="relative">
                            <button
                                onClick={() => setShowModelMenu(!showModelMenu)}
                                className="flex items-center gap-2 px-3 py-1.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-lg text-xs font-medium text-zinc-300 transition-all min-w-[140px] justify-between"
                            >
                                <span className="truncate max-w-[120px]">
                                    {availableModels.find(m => m.id === selectedModel)?.name ? formatModelName(availableModels.find(m => m.id === selectedModel)!.name) : (selectedModel || t.chat.modelSelector)}
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
                                                    "w-full text-left px-3 py-2 rounded-lg text-xs font-medium transition-colors flex items-center gap-2",
                                                    selectedModel === m.id ? "bg-indigo-600 text-white" : "text-zinc-400 hover:text-zinc-200 hover:bg-white/5"
                                                )}
                                            >
                                                {/* Simple Icon Logic based on provider/name */}
                                                {m.providerId?.includes('openai') ? <Sparkles size={14} className={selectedModel === m.id ? "text-white" : "text-green-400"} /> :
                                                    m.providerId?.includes('anthropic') ? <Zap size={14} className={selectedModel === m.id ? "text-white" : "text-amber-400"} /> :
                                                        m.providerId?.includes('google') ? <Bot size={14} className={selectedModel === m.id ? "text-white" : "text-blue-400"} /> :
                                                            <Cpu size={14} className={selectedModel === m.id ? "text-white" : "text-purple-400"} />
                                                }
                                                {formatModelName(m.name)}
                                            </button>
                                        ))}
                                    </div>
                                </>
                            )}
                        </div>

                        {/* Actions Menu */}
                        <div className="relative">
                            <button
                                onClick={() => setShowActionsMenu(!showActionsMenu)}
                                className="p-2 hover:bg-white/5 rounded-lg text-zinc-400 hover:text-white transition-colors"
                            >
                                <MoreVertical size={18} />
                            </button>
                            {showActionsMenu && (
                                <>
                                    <div className="fixed inset-0 z-10" onClick={() => setShowActionsMenu(false)} />
                                    <div className="absolute top-full right-0 mt-2 w-48 bg-[#18181b] border border-white/10 rounded-xl shadow-2xl z-20 overflow-hidden py-1">
                                        <button
                                            onClick={() => { handleDeleteSession(); setShowActionsMenu(false); }}
                                            className="w-full text-left px-4 py-2.5 text-xs font-medium text-red-400 hover:bg-red-500/10 flex items-center gap-2 transition-colors"
                                        >
                                            <Trash2 size={14} />
                                            {t.chat.deleteSession || 'Delete Session'}
                                        </button>
                                    </div>
                                </>
                            )}
                        </div>
                    </div>
                </div>

                <div className="flex items-center gap-2 px-6 pb-3 overflow-x-auto no-scrollbar">
                    <button onClick={() => setRagEnabled(!ragEnabled)} title={t.chat.ragTooltip} className={clsx("flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0", ragEnabled ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20" : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10")}>
                        <Bot size={12} /> {t.chat.rag}
                    </button>
                    <button onClick={() => setWebSearchEnabled(!webSearchEnabled)} title={t.chat.searchTooltip} className={clsx("flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0", webSearchEnabled ? "bg-cyan-500/10 text-cyan-400 border-cyan-500/20" : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10")}>
                        <Globe size={12} /> {t.chat.search}
                    </button>
                    <button onClick={() => setReasoningEnabled(!reasoningEnabled)} title={t.chat.thinkingTooltip} className={clsx("flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all border shrink-0", reasoningEnabled ? "bg-violet-500/10 text-violet-400 border-violet-500/20" : "bg-white/5 text-zinc-500 border-transparent hover:bg-white/10")}>
                        <BrainCircuit size={12} /> {t.chat.thinking}
                    </button>
                </div>
            </div>

            <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto p-4 md:p-6 scroll-smooth z-0 custom-scrollbar">
                <div className="max-w-4xl mx-auto space-y-6 pb-4">
                    {messages.length === 0 ? (
                        <div className="flex flex-col items-center justify-center py-20 opacity-30 space-y-4">
                            <div className="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center">
                                <Sparkles size={32} className="text-zinc-500" />
                            </div>
                            <p className="text-zinc-500">
                                {agentId ? `Start a new conversation with ${agentName}` : "Start creating"}
                            </p>
                        </div>
                    ) : (
                        messages.map(msg => (
                            <MessageBubble
                                key={msg.id}
                                message={msg}
                                isStreaming={msg.role === 'assistant' && generating && msg.id === messages[messages.length - 1].id}
                                onDelete={handleDeleteMessage}
                                onRegenerate={handleRegenerateMessage}
                            />
                        ))
                    )}
                    <div className="h-4" />
                </div>
            </div>

            {/* Scroll to Bottom FAB */}
            {showScrollButton && (
                <button
                    onClick={scrollToBottom}
                    className="absolute bottom-24 right-6 p-3 bg-indigo-600/90 hover:bg-indigo-500 text-white rounded-full shadow-lg backdrop-blur-sm transition-all animate-in fade-in zoom-in duration-200 z-20 border border-white/10"
                >
                    <ArrowDown size={20} />
                </button>
            )}

            <div className="p-4 md:p-6 bg-linear-to-t from-[#09090b] via-[#09090b] to-transparent z-10">
                <div className="max-w-4xl mx-auto relative group">
                    <div className="absolute -inset-0.5 bg-linear-to-r from-indigo-500 to-purple-500 rounded-2xl opacity-0 group-focus-within:opacity-30 transition-opacity blur duration-700 pointer-events-none" />
                    <form onSubmit={handleSend} className="relative flex items-center gap-2 bg-[#18181b] p-2 rounded-2xl border border-white/10 shadow-2xl focus-within:border-white/20 transition-colors">
                        <button type="button" className="p-2.5 text-zinc-400 hover:text-white hover:bg-white/5 rounded-xl transition-all"><ImageIcon size={20} /></button>
                        <textarea
                            ref={inputRef}
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(e); } }}
                            placeholder={status === 'idle' ? t.chat.placeholder : t.chat.generating}
                            className="flex-1 max-h-32 min-h-[44px] py-2.5 px-2 bg-transparent border-none outline-none focus:outline-none focus:ring-0 text-gray-100 placeholder-zinc-600 resize-none font-sans text-[15px] leading-relaxed scrollbar-hide transition-[height] duration-200 ease-out"
                            rows={1}
                        />
                        {generating ? (
                            <button type="button" onClick={handleAbort} className="p-2.5 bg-red-500/20 text-red-400 hover:bg-red-500/30 rounded-xl transition-all border border-red-500/20"><StopCircle size={20} className="animate-pulse" /></button>
                        ) : (
                            <button type="submit" disabled={!input.trim()} className="p-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:bg-zinc-800 disabled:text-zinc-600 text-white rounded-xl transition-all shadow-lg shadow-indigo-600/20"><Send size={20} /></button>
                        )}
                    </form>
                </div>
            </div>
        </div >
    );
}
