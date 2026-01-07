import { useState, useEffect, useRef } from 'react';

import { MessageSquare, Plus, Clock, ChevronRight, History } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { workbenchClient } from '../../services/WorkbenchClient';
import { GlassCard } from '../ui/glass-card';


interface AgentHistoryPopoverProps {
    agentId: string;
    agentName: string;
    agentColor?: string;
    isOpen: boolean;
    onClose: () => void;
    anchorRect: DOMRect | null;
}

export function AgentHistoryPopover({ agentId, agentName, isOpen, onClose, anchorRect }: AgentHistoryPopoverProps) {
    const navigate = useNavigate();
    const [sessions, setSessions] = useState<any[]>([]);
    const [loading, setLoading] = useState(false);
    const popoverRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (isOpen) {
            loadSessions();
        }
    }, [isOpen]);

    // Close on click outside
    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (popoverRef.current && !popoverRef.current.contains(event.target as Node)) {
                onClose();
            }
        };

        if (isOpen) {
            document.addEventListener('mousedown', handleClickOutside);
        }
        return () => {
            document.removeEventListener('mousedown', handleClickOutside);
        };
    }, [isOpen, onClose]);

    const loadSessions = async () => {
        setLoading(true);
        try {
            const allSessions = await workbenchClient.getSessions();
            // Filter by agent and sort by date
            const agentSessions = allSessions
                .filter((s: any) => s.agentId === agentId)
                .slice(0, 5); // Limit to 5 recent
            setSessions(agentSessions);
        } catch (e) {
            console.error('Failed to load sessions', e);
        } finally {
            setLoading(false);
        }
    };

    const handleNewChat = () => {
        // Navigate to /agent/:id which creates new
        // OR manually create session and navigate to /chat/:id? 
        // /agent/:id handles logic, so sticking to it
        navigate(`/agent/${agentId}`);
        onClose();
    };

    const handleOpenSession = (sessionId: string) => {
        navigate(`/chat/${sessionId}`);
        onClose();
    };

    if (!isOpen || !anchorRect) return null;

    // Calculate position: Right of the rail (Rail width ~72px)
    const top = anchorRect.top;
    const left = 80; // 72px rail + margin

    return (
        <AnimatePresence>
            {isOpen && (
                <div className="fixed inset-0 z-100 pointer-events-none">
                    {/* Backdrop for click outside is handled by event listener, but maybe we want a transparent blocker? No, let user interact with BG if needed? No, standard modal behavior. */}

                    <motion.div
                        ref={popoverRef}
                        initial={{ opacity: 0, x: -10, scale: 0.95 }}
                        animate={{ opacity: 1, x: 0, scale: 1 }}
                        exit={{ opacity: 0, x: -10, scale: 0.95 }}
                        transition={{ type: "spring", stiffness: 300, damping: 30 }}
                        className="absolute pointer-events-auto"
                        style={{ top: Math.min(top, window.innerHeight - 300), left }}
                    >
                        <GlassCard className="w-72 p-0 overflow-hidden border-zinc-700/50 shadow-2xl shadow-black/50 bg-[#18181b]/90 backdrop-blur-xl">
                            {/* Header */}
                            <div className="p-4 border-b border-white/5 bg-white/5 flex items-center justify-between">
                                <div className="flex items-center gap-2">
                                    <History size={16} className="text-zinc-400" />
                                    <span className="text-sm font-bold text-white">{agentName}</span>
                                </div>
                                <button
                                    onClick={handleNewChat}
                                    className="p-1.5 rounded-lg bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-400 transition-colors"
                                    title="New Chat"
                                >
                                    <Plus size={16} />
                                </button>
                            </div>

                            {/* Content */}
                            <div className="p-2 space-y-1 max-h-[300px] overflow-y-auto custom-scrollbar">
                                {loading ? (
                                    <div className="flex justify-center p-4">
                                        <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-indigo-500"></div>
                                    </div>
                                ) : sessions.length > 0 ? (
                                    sessions.map((session) => (
                                        <button
                                            key={session.id}
                                            onClick={() => handleOpenSession(session.id)}
                                            className="w-full text-left p-3 rounded-xl hover:bg-white/5 transition-all group flex items-start gap-3 border border-transparent hover:border-white/5"
                                        >
                                            <MessageSquare size={16} className="mt-0.5 text-zinc-500 group-hover:text-indigo-400 transition-colors" />
                                            <div className="flex-1 min-w-0">
                                                <div className="text-sm font-medium text-zinc-200 truncate group-hover:text-white transition-colors">
                                                    {session.title || 'Untitled Session'}
                                                </div>
                                                <div className="flex items-center gap-2 mt-1">
                                                    <span className="text-[10px] text-zinc-500 flex items-center gap-1">
                                                        <Clock size={10} />
                                                        {new Date(session.updatedAt || session.createdAt).toLocaleDateString()}
                                                    </span>
                                                    {session.modelId && (
                                                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-white/5 text-zinc-500 font-mono">
                                                            {session.modelId}
                                                        </span>
                                                    )}
                                                </div>
                                            </div>
                                            <ChevronRight size={14} className="text-zinc-600 opacity-0 group-hover:opacity-100 transition-all self-center" />
                                        </button>
                                    ))
                                ) : (
                                    <div className="text-center py-6 text-zinc-500 text-xs">
                                        No recent history
                                        <button onClick={handleNewChat} className="block mx-auto mt-2 text-indigo-400 hover:underline">
                                            Start new chat
                                        </button>
                                    </div>
                                )}
                            </div>

                            {/* Footer Link to full history? */}
                            {/* For now just simple list */}
                        </GlassCard>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
