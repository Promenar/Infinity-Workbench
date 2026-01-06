import { create } from 'zustand';

interface WorkbenchState {
    serverStatus: 'idle' | 'starting' | 'running' | 'error';
    serverUrl: string | null;
    accessCode: string | null;
    connectedClients: number;

    setServerStatus: (status: WorkbenchState['serverStatus']) => void;
    setServerUrl: (url: string | null) => void;
    setAccessCode: (code: string | null) => void;
    setConnectedClients: (count: number) => void;
    incrementClients: () => void;
    decrementClients: () => void;
}

export const useWorkbenchStore = create<WorkbenchState>((set) => ({
    serverStatus: 'idle',
    serverUrl: null,
    accessCode: null,
    connectedClients: 0,

    setServerStatus: (status) => set({ serverStatus: status }),
    setServerUrl: (url) => set({ serverUrl: url }),
    setAccessCode: (code) => set({ accessCode: code }),
    setConnectedClients: (count) => set({ connectedClients: count }),
    incrementClients: () => set((state) => ({ connectedClients: state.connectedClients + 1 })),
    decrementClients: () => set((state) => ({ connectedClients: Math.max(0, state.connectedClients - 1) })),
}));
