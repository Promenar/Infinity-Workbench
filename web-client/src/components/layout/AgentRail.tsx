import { useState, useEffect } from 'react';
import { Bot, Plus, Settings, Sparkles, Book } from 'lucide-react';
import { clsx } from 'clsx';
import { useNavigate, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { storeService, type Assistant } from '../../services/StoreService';
import { AgentHistoryPopover } from './AgentHistoryPopover';

interface AgentRailProps {
    className?: string;
}

export function AgentRail({ className }: AgentRailProps) {
    const navigate = useNavigate();
    const location = useLocation();
    const [assistants, setAssistants] = useState<Assistant[]>([]);
    const [activePopover, setActivePopover] = useState<{ agentId: string, rect: DOMRect, agent: Assistant } | null>(null);

    const [hoveredLabel, setHoveredLabel] = useState<{ text: string, top: number } | null>(null);

    useEffect(() => {
        const updateAssistants = (list: any) => setAssistants(list);
        storeService.on('assistants_updated', updateAssistants);
        setAssistants(storeService.getAssistants()); // Initial
        return () => { storeService.off('assistants_updated', updateAssistants); };
    }, []);

    // Filter for pinned or active (logic to be enhanced, likely just top 5 + Super for now)
    // EXCLUDE Super Assistant from this list as it has a dedicated button at the top
    const displayAgents = assistants
        .filter(a => a.id !== 'super_assistant')
        .slice(0, 8);

    const isActive = (path: string) => location.pathname === path || location.pathname.startsWith(path + '/');
    const isSuperActive = isActive('/chat/super_assistant');

    const handleAgentClick = (e: React.MouseEvent, agent: Assistant) => {
        e.stopPropagation();
        if (activePopover?.agentId === agent.id) {
            setActivePopover(null);
        } else {
            const rect = e.currentTarget.getBoundingClientRect();
            setActivePopover({ agentId: agent.id, rect, agent });
        }
    };

    const onMouseEnter = (e: React.MouseEvent, text: string) => {
        const rect = e.currentTarget.getBoundingClientRect();
        setHoveredLabel({ text, top: rect.top + rect.height / 2 });
    };

    const onMouseLeave = () => {
        setHoveredLabel(null);
    };

    return (
        <div className={clsx(
            "w-[72px] flex flex-col items-center py-6 gap-6 z-50",
            "border-r border-white/10 bg-black/40 backdrop-blur-xl", // Rail Glass
            className
        )}>
            {/* Super Assistant (Home/Global) */}
            <div className="relative group">
                {isSuperActive && (
                    <motion.div
                        layoutId="active-rail-indicator"
                        className="absolute -left-3 top-0 bottom-0 w-1 bg-indigo-500 rounded-r-full"
                    />
                )}
                <button
                    onClick={() => navigate('/chat/super_assistant')}
                    onMouseEnter={(e) => onMouseEnter(e, 'Super Assistant')}
                    onMouseLeave={onMouseLeave}
                    className={clsx(
                        "w-12 h-12 rounded-2xl flex items-center justify-center transition-all duration-300 relative group",
                        isSuperActive
                            ? "bg-indigo-600 text-white shadow-[0_0_20px_rgba(99,102,241,0.5)]"
                            : "bg-white/5 text-zinc-400 hover:bg-white/10 hover:text-white"
                    )}
                >
                    <Sparkles size={24} />
                </button>
            </div>

            <div className="w-8 h-px bg-white/10 rounded-full" />

            {/* Agent List */}
            <div className="flex-1 flex flex-col gap-4 overflow-y-auto w-full items-center no-scrollbar px-2 py-2 overflow-x-hidden">
                {displayAgents.map(agent => {
                    const active = activePopover?.agentId === agent.id;
                    return (
                        <div key={agent.id} className="relative group w-full flex justify-center">
                            <button
                                onClick={(e) => handleAgentClick(e, agent)}
                                onMouseEnter={(e) => onMouseEnter(e, agent.name)}
                                onMouseLeave={onMouseLeave}
                                className={clsx(
                                    "w-10 h-10 rounded-xl flex items-center justify-center transition-all duration-300",
                                    active
                                        ? "bg-white ring-2 ring-indigo-500 ring-offset-2 ring-offset-black"
                                        : "bg-white/5 text-zinc-400 hover:bg-white/10 hover:text-white hover:scale-105"
                                )}
                                style={{ backgroundColor: agent.color ? `${agent.color}20` : undefined }} // 20% opacity color
                            >
                                <Bot size={20} style={{ color: agent.color }} />
                            </button>
                        </div>
                    );
                })}

                {/* Add Agent */}
                <button
                    onClick={() => navigate('/')} // Back to Plaza for adding?
                    onMouseEnter={(e) => onMouseEnter(e, 'New Agent')}
                    onMouseLeave={onMouseLeave}
                    className="w-10 h-10 rounded-full border border-dashed border-white/20 flex items-center justify-center text-zinc-500 hover:text-white hover:border-white/50 transition-colors mt-2 shrink-0"
                >
                    <Plus size={18} />
                </button>
            </div>

            {/* Bottom Actions */}
            <div className="flex flex-col gap-4">
                <div className="relative group flex justify-center w-full">
                    <button
                        onClick={() => navigate('/library')}
                        onMouseEnter={(e) => onMouseEnter(e, 'Library')}
                        onMouseLeave={onMouseLeave}
                        className={clsx(
                            "w-10 h-10 rounded-xl flex items-center justify-center transition-colors",
                            isActive('/library') ? "bg-white/10 text-white" : "text-zinc-500 hover:text-white"
                        )}
                    >
                        <Book size={20} />
                    </button>
                </div>

                <div className="relative group flex justify-center w-full">
                    <button
                        onClick={() => navigate('/settings')}
                        onMouseEnter={(e) => onMouseEnter(e, 'Settings')}
                        onMouseLeave={onMouseLeave}
                        className={clsx(
                            "w-10 h-10 rounded-xl flex items-center justify-center transition-colors",
                            isActive('/settings') ? "bg-white/10 text-white" : "text-zinc-500 hover:text-white"
                        )}
                    >
                        <Settings size={20} />
                    </button>
                </div>
            </div>

            {/* History Popover */}
            {activePopover && (
                <AgentHistoryPopover
                    agentId={activePopover.agentId}
                    agentName={activePopover.agent.name}
                    agentColor={activePopover.agent.color}
                    isOpen={true}
                    onClose={() => setActivePopover(null)}
                    anchorRect={activePopover.rect}
                />
            )}

            {/* Fixed Tooltip Overlay */}
            {hoveredLabel && (
                <div
                    className="fixed left-[80px] px-3 py-1.5 bg-zinc-900/90 backdrop-blur border border-white/10 rounded-lg text-xs font-medium text-white shadow-xl z-60 pointer-events-none whitespace-nowrap"
                    style={{ top: hoveredLabel.top, transform: 'translateY(-50%)' }}
                >
                    {hoveredLabel.text}
                    {/* Tiny Arrow */}
                    <div className="absolute left-0 top-1/2 -translate-x-[4px] -translate-y-1/2 w-2 h-2 bg-zinc-900 border-l border-b border-white/10 rotate-45" />
                </div>
            )}
        </div>
    );
}
