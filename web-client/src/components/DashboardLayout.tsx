import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate, Link } from 'react-router-dom';
import {
    LayoutDashboard,
    Bot,
    MessageSquare,
    Book,
    Network,
    Settings,
    LogOut,
    Menu,
    X,
    ChevronRight,
    ChevronDown
} from 'lucide-react';
import clsx from 'clsx';
import { workbenchClient } from '../services/WorkbenchClient';
import { storeService, type Assistant, type Session } from '../services/StoreService';

export function DashboardLayout() {
    const [sidebarOpen, setSidebarOpen] = useState(false);
    const [connectionStatus, setConnectionStatus] = useState(workbenchClient.getStatus());

    // Data
    const [assistants, setAssistants] = useState<Assistant[]>([]);
    const [sessionsByAgent, setSessionsByAgent] = useState<Record<string, Session[]>>({});
    const [expandedAgents, setExpandedAgents] = useState<Record<string, boolean>>({});

    const location = useLocation();
    const navigate = useNavigate();

    useEffect(() => {
        // Status updates
        const updateStatus = (s: any) => setConnectionStatus(s);
        workbenchClient.on('statusChange', updateStatus);

        // Store updates
        const updateTree = (tree: any) => setSessionsByAgent(tree);
        const updateAssistants = (list: any) => {
            setAssistants(list);
            // Auto expand first one if empty?
        };

        storeService.on('tree_updated', updateTree);
        storeService.on('assistants_updated', updateAssistants);

        // Initial fetch
        setAssistants(storeService.getAssistants());
        setSessionsByAgent(storeService.getTree());

        return () => {
            workbenchClient.off('statusChange', updateStatus);
            storeService.off('tree_updated', updateTree);
            storeService.off('assistants_updated', updateAssistants);
        };
    }, []);

    const toggleAgent = (agentId: string) => {
        setExpandedAgents(prev => ({ ...prev, [agentId]: !prev[agentId] }));
    };

    const handleLogout = () => {
        workbenchClient.disconnect();
        localStorage.removeItem('wb_token');
        window.location.reload();
    };

    const isActive = (path: string) => location.pathname === path || location.pathname.startsWith(path + '/');

    // Navigation Items
    const mainNav = [
        { icon: LayoutDashboard, label: 'Dashboard', path: '/dashboard' }, // Keep for now
        { icon: Book, label: 'Library', path: '/library' },
        { icon: Network, label: 'Knowledge Graph', path: '/graph' },
        { icon: Settings, label: 'Settings', path: '/settings' },
    ];

    return (
        <div className="flex h-screen bg-gray-50 dark:bg-gray-900 overflow-hidden">
            {/* Mobile Sidebar Overlay */}
            {sidebarOpen && (
                <div
                    className="fixed inset-0 bg-black/50 z-20 md:hidden"
                    onClick={() => setSidebarOpen(false)}
                />
            )}

            {/* Sidebar */}
            <aside className={clsx(
                "fixed inset-y-0 left-0 z-30 w-72 flex flex-col transition-transform duration-300 ease-in-out md:relative md:translate-x-0",
                "border-r border-[rgba(255,255,255,0.08)] bg-[#09090b]/80 backdrop-blur-xl", // Glass effect
                sidebarOpen ? "translate-x-0" : "-translate-x-full"
            )}>
                {/* Header */}
                <div className="h-20 flex items-center px-6 border-b border-[rgba(255,255,255,0.08)]">
                    <div className="flex items-center gap-3">
                        <div className="w-9 h-9 bg-linear-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-lg shadow-indigo-500/20">
                            <Bot className="text-white" size={20} />
                        </div>
                        <span className="font-bold text-lg text-white tracking-tight">Workbench</span>
                    </div>
                    <button
                        onClick={() => setSidebarOpen(false)}
                        className="ml-auto md:hidden text-zinc-400 hover:text-white transition-colors"
                    >
                        <X size={20} />
                    </button>
                </div>

                {/* Scrollable Content */}
                <div className="flex-1 overflow-y-auto py-6 px-4 space-y-8 custom-scrollbar">

                    {/* Main Nav */}
                    <div className="space-y-1">
                        <div className="px-3 text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-2">Menu</div>
                        {mainNav.map(item => (
                            <Link
                                key={item.path}
                                to={item.path}
                                onClick={() => setSidebarOpen(false)}
                                className={clsx(
                                    "flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all duration-200 group relative overflow-hidden",
                                    isActive(item.path)
                                        ? "text-white bg-white/10 shadow-sm border border-white/5"
                                        : "text-zinc-400 hover:text-zinc-100 hover:bg-white/5"
                                )}
                            >
                                <item.icon size={18} className={clsx("transition-transform group-hover:scale-110", isActive(item.path) && "text-indigo-400")} />
                                <span>{item.label}</span>
                                {isActive(item.path) && (
                                    <div className="absolute inset-0 bg-linear-to-r from-indigo-500/10 to-transparent pointer-events-none" />
                                )}
                            </Link>
                        ))}
                    </div>

                    {/* Agents & Sessions Tree */}
                    <div>
                        <div className="flex items-center justify-between px-3 mb-3">
                            <h3 className="text-xs font-semibold text-zinc-500 uppercase tracking-wider">
                                Assistants
                            </h3>
                            <Link to="/assistants" className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">
                                Manage
                            </Link>
                        </div>

                        <div className="space-y-4">
                            {assistants.map(agent => {
                                const sessions = sessionsByAgent[agent.id] || [];
                                const isExpanded = expandedAgents[agent.id];

                                return (
                                    <div key={agent.id} className="space-y-1">
                                        <button
                                            onClick={() => toggleAgent(agent.id)}
                                            className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-zinc-300 hover:text-white hover:bg-white/5 transition-colors group"
                                        >
                                            <div className="p-1 rounded bg-zinc-800 text-zinc-400 group-hover:text-zinc-200 transition-colors">
                                                {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                                            </div>
                                            <span className="truncate flex-1 text-left">{agent.name}</span>
                                            {sessions.length > 0 && (
                                                <span className="text-[10px] font-bold text-zinc-500 bg-zinc-800/80 px-2 py-0.5 rounded-full border border-zinc-700/50">
                                                    {sessions.length}
                                                </span>
                                            )}
                                        </button>

                                        {isExpanded && (
                                            <div className="animate-slide-up pl-4 space-y-1 relative">
                                                {/* Connecting line */}
                                                <div className="absolute left-[20px] top-1 bottom-4 w-px bg-zinc-800" />

                                                {sessions.length === 0 ? (
                                                    <div className="pl-6 py-2 text-xs text-zinc-600 italic">No active sessions</div>
                                                ) : (
                                                    sessions.map(session => (
                                                        <Link
                                                            key={session.id}
                                                            to={`/chat/${session.id}`}
                                                            onClick={() => setSidebarOpen(false)}
                                                            className={clsx(
                                                                "block pl-6 pr-2 py-2 rounded-lg text-sm truncate transition-all border-l-2 ml-[19px]",
                                                                location.pathname === `/chat/${session.id}`
                                                                    ? "border-indigo-500 text-indigo-400 bg-indigo-500/10"
                                                                    : "border-transparent text-zinc-500 hover:text-zinc-300 hover:border-zinc-700"
                                                            )}
                                                        >
                                                            {session.title || 'Untitled Session'}
                                                        </Link>
                                                    ))
                                                )}
                                                <button
                                                    onClick={async () => {
                                                        const s = await workbenchClient.request('CMD_CREATE_SESSION', { agentId: agent.id });
                                                        if (s?.id) navigate(`/chat/${s.id}`);
                                                    }}
                                                    className="w-full pl-6 text-left py-2 text-xs text-indigo-400 hover:text-indigo-300 font-medium flex items-center gap-1.5 ml-[19px] border-l-2 border-transparent hover:border-zinc-700 transition-colors"
                                                >
                                                    <div className="w-4 h-4 rounded-full bg-indigo-500/10 flex items-center justify-center border border-indigo-500/20">
                                                        <MessageSquare size={10} />
                                                    </div>
                                                    <span>New Chat</span>
                                                </button>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}

                            {/* Unassigned Sessions (Debug/Fallback) */}
                            {sessionsByAgent['unknown'] && sessionsByAgent['unknown'].length > 0 && (
                                <div className="pt-4 border-t border-white/5 mt-4">
                                    <h3 className="px-3 text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-2">Unassigned</h3>
                                    {sessionsByAgent['unknown'].map(session => (
                                        <Link
                                            key={session.id}
                                            to={`/chat/${session.id}`}
                                            onClick={() => setSidebarOpen(false)}
                                            className={clsx(
                                                "block px-3 py-2 rounded-lg text-sm truncate transition-all",
                                                location.pathname === `/chat/${session.id}`
                                                    ? "text-indigo-400 bg-indigo-500/10"
                                                    : "text-zinc-500 hover:text-zinc-300 hover:bg-white/5"
                                            )}
                                        >
                                            {session.title || 'Untitled Session'}
                                        </Link>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Footer */}
                <div className="p-4 border-t border-[rgba(255,255,255,0.08)] bg-[#09090b]/40">
                    <div className="flex items-center gap-2 px-3 py-2 bg-zinc-900/50 border border-zinc-800/50 rounded-lg mb-3">
                        {connectionStatus === 'authenticated' ? (
                            <div className="w-2 h-2 rounded-full bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.5)]" />
                        ) : (
                            <div className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                        )}
                        <span className="text-xs font-medium text-zinc-400 capitalize">
                            {connectionStatus === 'authenticated' ? 'System Online' : connectionStatus}
                        </span>
                    </div>

                    <button
                        onClick={handleLogout}
                        className="w-full flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl text-sm font-medium text-zinc-400 hover:text-red-400 hover:bg-red-500/10 hover:border-red-500/20 border border-transparent transition-all duration-200"
                    >
                        <LogOut size={16} />
                        <span>Sign Out</span>
                    </button>
                </div>
            </aside>

            {/* Main Content */}
            <div className="flex-1 flex flex-col min-w-0 bg-[#09090b]">
                {/* Mobile Header Trigger */}
                <div className="md:hidden h-16 bg-[#18181b]/90 backdrop-blur border-b border-zinc-800 flex items-center px-4 sticky top-0 z-20">
                    <button onClick={() => setSidebarOpen(true)} className="p-2 -ml-2 text-gray-600">
                        <Menu size={24} />
                    </button>
                    <span className="font-bold text-gray-900 dark:text-white ml-2">Workbench</span>
                </div>

                <main className="flex-1 overflow-auto relative">
                    <Outlet />
                </main>
            </div>
        </div>
    );
}
