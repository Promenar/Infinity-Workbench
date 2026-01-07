import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { MessageSquare, Calendar, Search, Trash2, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

interface Session {
    id: string;
    title: string;
    agentId: string;
    updatedAt: number;
    lastMessage?: string;
}

export function Sessions() {
    const [sessions, setSessions] = useState<Session[]>([]);
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');
    const navigate = useNavigate();

    useEffect(() => {
        fetchSessions();

        const onMessage = (msg: any) => {
            if (msg.type === 'SESSION_LIST_UPDATED') {
                fetchSessions();
            }
        };

        workbenchClient.on('message', onMessage);
        return () => { workbenchClient.off('message', onMessage); };
    }, []);

    const fetchSessions = async () => {
        try {
            const list = await workbenchClient.request('CMD_GET_SESSIONS');
            setSessions(list.sort((a: Session, b: Session) => b.updatedAt - a.updatedAt));
        } catch (e) {
            console.error('Failed to fetch sessions', e);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async (e: React.MouseEvent, id: string) => {
        e.stopPropagation();
        if (!confirm('Are you sure you want to delete this session?')) return;
        try {
            await workbenchClient.request('CMD_DELETE_SESSION', { id });
            setSessions(prev => prev.filter(s => s.id !== id));
        } catch (err) {
            console.error('Delete failed', err);
        }
    };

    const handleCreate = async () => {
        try {
            const session = await workbenchClient.request('CMD_CREATE_SESSION');
            if (session && session.id) {
                navigate(`/chat/${session.id}`);
            }
        } catch (e) {
            console.error('Failed to create session', e);
        }
    };

    const filteredSessions = sessions.filter(s =>
        (s.title || 'New Chat').toLowerCase().includes(search.toLowerCase()) ||
        (s.lastMessage || '').toLowerCase().includes(search.toLowerCase())
    );

    if (loading) return (
        <div className="p-8 flex justify-center">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"></div>
        </div>
    );

    return (
        <div className="p-8 max-w-6xl mx-auto min-h-screen">
            <div className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-2xl font-bold text-white tracking-tight">Sessions</h1>
                    <p className="text-zinc-400 mt-1">Manage your conversation history</p>
                </div>
                <button
                    onClick={handleCreate}
                    className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-500 text-white px-4 py-2 rounded-xl transition-all shadow-lg shadow-indigo-500/20 active:scale-95"
                >
                    <Plus size={20} />
                    <span>New Chat</span>
                </button>
            </div>

            {/* Search Bar */}
            <div className="relative mb-8 group">
                <div className="absolute inset-0 bg-indigo-500/20 rounded-2xl blur-xl opacity-0 group-focus-within:opacity-100 transition-opacity duration-500" />
                <div className="relative">
                    <Search className="absolute left-4 top-1/2 -translate-y-1/2 text-zinc-500" size={20} />
                    <input
                        type="text"
                        placeholder="Search conversations..."
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="w-full pl-11 pr-4 py-3 bg-[#18181b]/60 backdrop-blur-xl border border-white/10 rounded-2xl text-white placeholder-zinc-500 focus:border-indigo-500/50 focus:bg-[#18181b]/80 outline-none transition-all"
                    />
                </div>
            </div>

            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                {filteredSessions.map(session => (
                    <div
                        key={session.id}
                        onClick={() => navigate(`/chat/${session.id}`)}
                        className="group relative bg-[#18181b]/40 hover:bg-[#18181b]/60 backdrop-blur-md rounded-2xl p-5 border border-white/5 hover:border-indigo-500/30 transition-all cursor-pointer overflow-hidden"
                    >
                        {/* Hover Gradient */}
                        <div className="absolute inset-0 bg-linear-to-br from-indigo-500/0 via-indigo-500/0 to-indigo-500/0 group-hover:to-indigo-500/5 transition-all duration-500" />

                        <div className="relative flex items-start justify-between mb-3">
                            <div className="p-2.5 bg-indigo-500/10 rounded-xl text-indigo-400 border border-indigo-500/10 group-hover:border-indigo-500/20 transition-colors">
                                <MessageSquare size={20} />
                            </div>
                            <button
                                onClick={(e) => handleDelete(e, session.id)}
                                className="p-2 text-zinc-500 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                            >
                                <Trash2 size={18} />
                            </button>
                        </div>

                        <h3 className="relative font-semibold text-white mb-2 line-clamp-1 group-hover:text-indigo-200 transition-colors">
                            {session.title || 'Untitled Session'}
                        </h3>

                        <p className="relative text-sm text-zinc-400 line-clamp-2 mb-4 h-10 group-hover:text-zinc-300 transition-colors">
                            {session.lastMessage || 'No messages yet'}
                        </p>

                        <div className="relative flex items-center gap-2 text-xs text-zinc-500 border-t border-white/5 pt-3">
                            <Calendar size={14} />
                            <span>{new Date(session.updatedAt).toLocaleDateString()}</span>
                            {session.agentId && (
                                <>
                                    <span className="ml-auto px-2 py-0.5 bg-white/5 rounded-full border border-white/5 text-zinc-400 group-hover:border-white/10 transition-colors">
                                        {session.agentId}
                                    </span>
                                </>
                            )}
                        </div>
                    </div>
                ))}

                {filteredSessions.length === 0 && (
                    <div className="col-span-full py-20 text-center text-zinc-500 bg-white/5 rounded-3xl border border-dashed border-white/10">
                        <MessageSquare size={48} className="mx-auto mb-4 opacity-20" />
                        <p className="text-lg font-medium text-zinc-400">No sessions found</p>
                        <button
                            onClick={handleCreate}
                            className="text-indigo-400 hover:text-indigo-300 font-medium mt-2"
                        >
                            Create your first session &rarr;
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}
