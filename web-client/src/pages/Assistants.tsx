import { useEffect, useState } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Bot, Plus, Search, MoreVertical, Edit2, Trash2 } from 'lucide-react';

interface Agent {
    id: string;
    name: string;
    description: string;
    avatar: string;
    color: string;
    defaultModel: string;
    isPreset?: boolean;
}

export const Assistants = () => {
    const [agents, setAgents] = useState<Agent[]>([]);
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState('');

    useEffect(() => {
        loadAgents();
    }, []);

    const loadAgents = async () => {
        try {
            const data = await workbenchClient.request('CMD_GET_AGENTS');
            setAgents(data);
        } catch (e) {
            console.error('Failed to load agents', e);
        } finally {
            setLoading(false);
        }
    };

    const filteredAgents = agents.filter(a =>
        a.name.toLowerCase().includes(search.toLowerCase()) ||
        a.description.toLowerCase().includes(search.toLowerCase())
    );

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-3xl font-bold text-gray-900 dark:text-white mb-2">Assistants</h1>
                    <p className="text-gray-500 dark:text-gray-400">Manage your AI assistants and personalities</p>
                </div>
                <button className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg transition-colors font-medium">
                    <Bot size={20} />
                    <span>Create Assistant</span>
                </button>
            </div>

            <div className="relative mb-8">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" size={20} />
                <input
                    type="text"
                    placeholder="Search assistants..."
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="w-full pl-10 pr-4 py-3 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-xl focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none transition-all"
                />
            </div>

            {loading ? (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-48 bg-gray-100 dark:bg-gray-800 rounded-2xl animate-pulse" />
                    ))}
                </div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                    {filteredAgents.map(agent => (
                        <div key={agent.id} className="group bg-white dark:bg-gray-800 p-6 rounded-2xl border border-gray-200 dark:border-gray-700 hover:border-indigo-500 dark:hover:border-indigo-500 transition-all hover:shadow-lg">
                            <div className="flex justify-between items-start mb-4">
                                <div
                                    className="w-12 h-12 rounded-xl flex items-center justify-center text-white text-xl shadow-lg"
                                    style={{ backgroundColor: agent.color || '#6366f1' }}
                                >
                                    {/* Ideally map avatar string to Lucide icon, fallback to Bot */}
                                    <Bot size={24} />
                                </div>
                                <div className="relative">
                                    <button className="p-2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 opacity-0 group-hover:opacity-100 transition-opacity">
                                        <MoreVertical size={20} />
                                    </button>
                                </div>
                            </div>

                            <h3 className="text-lg font-bold text-gray-900 dark:text-white mb-2">{agent.name}</h3>
                            <p className="text-gray-500 dark:text-gray-400 text-sm mb-4 line-clamp-2 min-h-[40px]">
                                {agent.description}
                            </p>

                            <div className="flex items-center justify-between pt-4 border-t border-gray-100 dark:border-gray-700">
                                <div className="text-xs font-medium px-2 py-1 rounded-md bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300">
                                    {agent.defaultModel}
                                </div>
                                {agent.isPreset ? (
                                    <span className="text-xs text-indigo-500 font-medium">System Preset</span>
                                ) : (
                                    <div className="flex gap-2">
                                        <button className="p-1.5 text-gray-400 hover:text-indigo-500 transition-colors">
                                            <Edit2 size={16} />
                                        </button>
                                        <button className="p-1.5 text-gray-400 hover:text-red-500 transition-colors">
                                            <Trash2 size={16} />
                                        </button>
                                    </div>
                                )}
                            </div>
                        </div>
                    ))}

                    {/* Add New Card */}
                    <button className="flex flex-col items-center justify-center h-full min-h-[240px] border-2 border-dashed border-gray-300 dark:border-gray-700 rounded-2xl hover:border-indigo-500 dark:hover:border-indigo-500 hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-all group text-gray-400 hover:text-indigo-500">
                        <div className="w-12 h-12 rounded-full bg-gray-100 dark:bg-gray-800 group-hover:bg-indigo-100 dark:group-hover:bg-indigo-900/30 flex items-center justify-center mb-3 transition-colors">
                            <Plus size={24} />
                        </div>
                        <span className="font-medium">Create New Assistant</span>
                    </button>
                </div>
            )}
        </div>
    );
};
