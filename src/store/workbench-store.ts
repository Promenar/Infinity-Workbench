import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

interface WorkbenchState {
    serverStatus: 'idle' | 'starting' | 'running' | 'error';
    serverUrl: string | null;
    accessCode: string | null;
    connectedClients: number;
    activeTokens: Record<string, number>; // Token -> Expiry

    setServerStatus: (status: WorkbenchState['serverStatus']) => void;
    setServerUrl: (url: string | null) => void;
    setAccessCode: (code: string | null) => void;
    setConnectedClients: (count: number) => void;
    addToken: (token: string, expiry: number) => void;
    removeToken: (token: string) => void;
    incrementClients: () => void;
    decrementClients: () => void;
}

export const useWorkbenchStore = create<WorkbenchState>()(
    persist(
        (set) => ({
            serverStatus: 'idle',
            serverUrl: null,
            accessCode: null,
            connectedClients: 0,
            activeTokens: {},

            setServerStatus: (status) => set({ serverStatus: status }),
            setServerUrl: (url) => set({ serverUrl: url }),
            setAccessCode: (code) => set({ accessCode: code }),
            setConnectedClients: (count) => set({ connectedClients: count }),
            addToken: (token, expiry) => set((state) => ({
                activeTokens: { ...state.activeTokens, [token]: expiry }
            })),
            removeToken: (token) => set((state) => {
                const newTokens = { ...state.activeTokens };
                delete newTokens[token];
                return { activeTokens: newTokens };
            }),
            incrementClients: () => set((state) => ({ connectedClients: state.connectedClients + 1 })),
            decrementClients: () => set((state) => ({ connectedClients: Math.max(0, state.connectedClients - 1) })),
        }),
        {
            name: 'workbench-storage',
            storage: createJSONStorage(() => AsyncStorage),
            partialize: (state) => ({
                accessCode: state.accessCode,
                activeTokens: state.activeTokens
            }),
        }
    )
);
