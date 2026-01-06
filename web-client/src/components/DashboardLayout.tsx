import { useEffect, useState } from 'react';
import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import {
    LayoutDashboard,
    MessageSquare,
    Bot,
    Library,
    Settings,
    LogOut,
    Network
} from 'lucide-react';
import { workbenchClient, type ConnectionStatus } from '../services/WorkbenchClient';

export function DashboardLayout() {
    const navigate = useNavigate();

    const [status, setStatus] = useState<ConnectionStatus>(workbenchClient.getStatus());

    useEffect(() => {
        const onStatusChange = (s: ConnectionStatus) => {
            setStatus(s);
            if (s === 'disconnected' || s === 'error') {
                navigate('/');
            }
        };
        workbenchClient.on('statusChange', onStatusChange);
        return () => {
            workbenchClient.off('statusChange', onStatusChange);
        };
    }, [navigate]);

    const handleLogout = () => {
        workbenchClient.disconnect();
        navigate('/');
    };

    const navItems = [
        { to: '/dashboard', icon: LayoutDashboard, label: 'Overview' },
        { to: '/assistants', icon: Bot, label: 'Assistants' },
        { to: '/sessions', icon: MessageSquare, label: 'Sessions' },
        { to: '/library', icon: Library, label: 'Library' },
        { to: '/settings', icon: Settings, label: 'Settings' },
    ];

    return (
        <div className="flex h-screen bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100">
            {/* Sidebar */}
            <aside className="w-64 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 flex flex-col">
                <div className="p-6 border-b border-gray-200 dark:border-gray-700">
                    <div className="flex items-center gap-2">
                        <div className="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center">
                            <Network className="text-white w-5 h-5" />
                        </div>
                        <h1 className="font-bold text-xl tracking-tight">Nexara</h1>
                    </div>
                    <div className="mt-4 flex items-center gap-2 text-xs font-medium px-2 py-1 bg-gray-100 dark:bg-gray-700 rounded-md text-gray-500 dark:text-gray-400">
                        <div className={`w-2 h-2 rounded-full ${status === 'authenticated' ? 'bg-green-500' : 'bg-red-500'}`} />
                        {status === 'authenticated' ? 'Connected' : 'Disconnected'}
                    </div>
                </div>

                <nav className="flex-1 p-4 space-y-1">
                    {navItems.map((item) => (
                        <NavLink
                            key={item.to}
                            to={item.to}
                            className={({ isActive }) =>
                                `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${isActive
                                    ? 'bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400'
                                    : 'text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700'
                                }`
                            }
                        >
                            <item.icon size={18} />
                            {item.label}
                        </NavLink>
                    ))}
                </nav>

                <div className="p-4 border-t border-gray-200 dark:border-gray-700">
                    <button
                        onClick={handleLogout}
                        className="flex items-center gap-3 px-3 py-2.5 w-full rounded-lg text-sm font-medium text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                    >
                        <LogOut size={18} />
                        Disconnect
                    </button>
                </div>
            </aside>

            {/* Main Content */}
            <main className="flex-1 overflow-auto relative">
                <Outlet />
            </main>
        </div>
    );
}
