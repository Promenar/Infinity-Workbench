import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { produce } from 'immer';

export interface McpServerConfig {
    id: string;
    name: string;
    url: string; // SSE Endpoint
    enabled: boolean;
    defaultIncluded: boolean; // 是否默认包含在新会话中
    lastSync?: number;
    status: 'connected' | 'disconnected' | 'error' | 'loading';
    error?: string;
    callInterval?: number; // 🆕 调用间隔 (秒)
    lastCallTimestamp?: number; // 🆕 上次调用时间戳 (ms)
}

interface McpState {
    servers: McpServerConfig[];

    // CRUD
    addServer: (server: Omit<McpServerConfig, 'status'>) => void;
    updateServer: (id: string, updates: Partial<McpServerConfig>) => void;
    removeServer: (id: string) => void;

    // UI Helpers
    setServerStatus: (id: string, status: McpServerConfig['status'], error?: string) => void;
}

export const useMcpStore = create<McpState>()(
    persist(
        (set) => ({
            servers: [],

            addServer: (server) => set(produce((state: McpState) => {
                if (state.servers.find(s => s.id === server.id)) return;
                state.servers.push({ ...server, status: 'disconnected' });
            })),

            updateServer: (id, updates) => set(produce((state: McpState) => {
                const index = state.servers.findIndex(s => s.id === id);
                if (index !== -1) {
                    state.servers[index] = { ...state.servers[index], ...updates };
                }
            })),

            removeServer: (id) => set(produce((state: McpState) => {
                state.servers = state.servers.filter(s => s.id !== id);
            })),

            setServerStatus: (id, status, error) => set(produce((state: McpState) => {
                const server = state.servers.find(s => s.id === id);
                if (server) {
                    server.status = status;
                    if (error !== undefined) {
                        server.error = error;
                    } else if (status === 'connected' || status === 'loading') {
                        // 🔑 关键修复：在成功连接或进入加载态时清除历史错误
                        server.error = undefined;
                    }
                }
            })),
        }),
        {
            name: 'mcp-storage',
            storage: createJSONStorage(() => AsyncStorage),
        }
    )
);
