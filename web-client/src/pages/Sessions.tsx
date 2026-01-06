import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { MessageSquare, Calendar, Search, Trash2 } from 'lucide-react';


interface Session {
    id: string;
    title: string;
    agentId: string;
    updatedAt: number;
    lastMessage?: string;
}

export const Sessions = () => {
    const [sessions, setSessions] = useState<Session[]>([]);
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');

    useEffect(() => {
        loadSessions();

        // Listen for updates
        const onMessage = (msg: any) => {
            if (msg.type === 'SESSION_UPDATED') loadSessions();
        };
        workbenchClient.on('message', onMessage);
        return () => { workbenchClient.off('message', onMessage); };
    }, []);

    const loadSessions = async () => {
        try {
            const data = await workbenchClient.request('CMD_GET_SESSIONS');
            setSessions(data);
        } catch (e) {
            console.error('Failed to load sessions', e);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async (e: React.MouseEvent, id: string) => {
        e.stopPropagation();
        if (!confirm('Delete this session?')) return;
        try {
            await workbenchClient.request('CMD_DELETE_SESSION', { id });
            setSessions(prev => prev.filter(s => s.id !== id));
        } catch (err) {
            console.error('Delete failed', err);
        }
    };

    const filteredSessions = sessions.filter(s =>
        (s.title || 'New Chat').toLowerCase().includes(search.toLowerCase()) ||
        (s.lastMessage || '').toLowerCase().includes(search.toLowerCase())
    );

    const formatDate = (ts: number) => {
        return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-3xl font-bold text-gray-900 dark:text-white mb-2">Sessions</h1>
                    <p className="text-gray-500 dark:text-gray-400">Manage your conversation history</p>
                </div>
            </div>

            <div className="relative mb-8">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" size={20} />
                <input
                    type="text"
                    placeholder="Search conversations..."
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="w-full pl-10 pr-4 py-3 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none transition-all"
                />
            </div>

            {loading ? (
                <div className="space-y-4">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-24 bg-gray-100 dark:bg-gray-800 rounded-xl animate-pulse" />
                    ))}
                </div>
            ) : (
                <div className="space-y-4">
                    {filteredSessions.map(session => (
                        <div
                            key={session.id}
                            onClick={() => console.log('Navigate to session', session.id)}
                            className="group bg-white dark:bg-gray-800 p-5 rounded-xl border border-gray-200 dark:border-gray-700 hover:border-indigo-500 dark:hover:border-indigo-500 transition-all hover:shadow-md cursor-pointer flex justify-between items-center"
                        >
                            <div className="flex items-start gap-4 overflow-hidden">
                                <div className="p-3 bg-indigo-50 dark:bg-indigo-900/20 rounded-lg text-indigo-600 dark:text-indigo-400">
                                    <MessageSquare size={24} />
                                </div>
                                <div className="min-w-0">
                                    <h3 className="font-bold text-gray-900 dark:text-white mb-1 truncate pr-4">
                                        {session.title || 'New Chat'}
                                    </h3>
                                    <p className="text-gray-500 dark:text-gray-400 text-sm truncate">
                                        {session.lastMessage || 'No messages yet'}
                                    </p>
                                    <div className="flex items-center gap-4 mt-2 text-xs text-gray-400">
                                        <span className="flex items-center gap-1">
                                            <Calendar size={12} />
                                            {formatDate(session.updatedAt)}
                                        </span>
                                        <span className="px-2 py-0.5 bg-gray-100 dark:bg-gray-700 rounded text-gray-500 dark:text-gray-300 font-medium">
                                            {session.agentId}
                                        </span>
                                    </div>
                                </div>
                            </div>

                            <button
                                onClick={(e) => handleDelete(e, session.id)}
                                className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg opacity-0 group-hover:opacity-100 transition-all"
                                title="Delete Session"
                            >
                                <Trash2 size={20} />
                            </button>
                        </div>
                    ))}

                    {filteredSessions.length === 0 && (
                        <div className="text-center py-12 text-gray-400">
                            <MessageSquare size={48} className="mx-auto mb-4 opacity-20" />
                            <p>No sessions found</p>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};
