import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Agent, AgentId } from '../types/chat';
import { getPresetAgents } from '../lib/agent-presets';

interface AgentState {
  agents: Agent[];
  addAgent: (agent: Agent) => void;
  updateAgent: (id: AgentId, updates: Partial<Agent>) => void;
  deleteAgent: (id: AgentId) => void;
  togglePinAgent: (id: AgentId) => void;
  getAgent: (id: AgentId) => Agent | undefined;
  initializeAgents: (lang: 'en' | 'zh') => void;
}

export const useAgentStore = create<AgentState>()(
  persist(
    (set, get) => ({
      agents: [], // Empty initially, waiting for hydration via welcome screen

      initializeAgents: (lang) => {
        const currentAgents = get().agents;
        // Only initialize if absolutely empty (First Launch)
        if (currentAgents.length === 0) {
          const presets = getPresetAgents(lang);
          set({ agents: presets });
          console.log(`[AgentStore] Initialized ${presets.length} agents in ${lang}`);
        }
      },

      addAgent: (agent) => set((state) => ({ agents: [...state.agents, agent] })),

      updateAgent: (id, updates) => {
        set((state) => {
          const exists = state.agents.some((a) => a.id === id);
          if (exists) {
            return {
              agents: state.agents.map((a) => (a.id === id ? { ...a, ...updates } : a)),
            };
          } else {
            // Fallback recovery for presets
            const presetAgent = getPresetAgents('en').find((a) => a.id === id);
            if (presetAgent) {
              return {
                agents: [...state.agents, { ...presetAgent, ...updates }],
              };
            }
            return state;
          }
        });
      },

      deleteAgent: (id) =>
        set((state) => ({
          agents: state.agents.filter((a) => a.id !== id),
        })),

      togglePinAgent: (id) =>
        set((state) => ({
          agents: state.agents.map((a) => (a.id === id ? { ...a, isPinned: !a.isPinned } : a)),
        })),

      getAgent: (id) => {
        const stateAgent = get().agents.find((a) => a.id === id);
        if (stateAgent) return stateAgent;
        // Fallback for extreme edge cases
        return getPresetAgents('en').find((a) => a.id === id);
      },
    }),
    {
      name: 'agent-storage',
      storage: createJSONStorage(() => AsyncStorage),
      onRehydrateStorage: (state) => {
        return (rehydratedState, error) => {
          if (!error && rehydratedState) {
            console.log('[NativeSync] Rehydration complete, syncing agents...');
            syncAgentsToNative(rehydratedState.agents);
          }
        };
      },
    },
  ),
);

// ──────────────────────────────────────────
// Native Bridge Synchronization
// ──────────────────────────────────────────
import { syncAgentsToNative } from '../native/NexaraBridge';

useAgentStore.subscribe((state) => {
  console.log(`[NativeSync] Syncing ${state.agents.length} agents to Native`);
  syncAgentsToNative(state.agents);
});
