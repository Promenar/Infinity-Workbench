import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Agent, AgentId } from '../types/chat';

interface AgentState {
    agents: Agent[];
    addAgent: (agent: Agent) => void;
    updateAgent: (id: AgentId, updates: Partial<Agent>) => void;
    deleteAgent: (id: AgentId) => void;
    togglePinAgent: (id: AgentId) => void;
    getAgent: (id: AgentId) => Agent | undefined;
}

const PRESET_AGENTS: Agent[] = [
    {
        id: 'neuralflow_default',
        name: 'NeuralFlow Assistant',
        description: 'Your general purpose AI assistant for all tasks.',
        avatar: 'MessageSquare',
        color: '#6366f1',
        systemPrompt: 'You are NeuralFlow, a helpful and intelligent AI assistant.',
        defaultModel: 'gpt-4o',
        params: { temperature: 0.7 },
        isPreset: true,
        created: Date.now(),
    },
    {
        id: 'translator_pro',
        name: 'Translator Pro',
        description: 'Expert in multi-language translation and localization.',
        avatar: 'Languages',
        color: '#10b981',
        systemPrompt: 'You are a professional translator. Translate the given text accurately while maintaining the original tone and cultural nuances.',
        defaultModel: 'gpt-4o',
        params: { temperature: 0.3 },
        isPreset: true,
        created: Date.now(),
    },
    {
        id: 'code_mentor',
        name: 'Code Mentor',
        description: 'A programmer aide to help with debugging and architecture.',
        avatar: 'Code2',
        color: '#8b5cf6',
        systemPrompt: 'You are an expert software engineer. Provide clear, efficient, and well-documted code solutions and architectural advice.',
        defaultModel: 'claude-3-opus',
        params: { temperature: 0.2 },
        isPreset: true,
        created: Date.now(),
    },
    {
        id: 'creative_writer',
        name: 'Creative Writer',
        description: 'Your companion for storytelling, poetry, and creative content.',
        avatar: 'PenTool',
        color: '#f43f5e',
        systemPrompt: 'You are a creative writer. Use evocative language and vivid imagery to craft compelling stories and poems.',
        defaultModel: 'gpt-4o',
        params: { temperature: 0.9 },
        created: Date.now(),
    },
    {
        id: 'super_assistant',
        name: 'Super Assistant',
        description: 'Global personal assistant with access to all knowledge and history.',
        avatar: 'Sparkles',
        color: '#8b5cf6',
        systemPrompt: 'You are the Super Assistant, a unique and powerful AI agent. You have access to all conversation history and all documents in the knowledge base. Provide comprehensive and context-aware assistance.',
        defaultModel: 'gpt-4o',
        params: { temperature: 0.7 },
        isPreset: true,
        created: Date.now(),
    }
];

export const useAgentStore = create<AgentState>()(
    persist(
        (set, get) => ({
            agents: PRESET_AGENTS,
            addAgent: (agent) => set((state) => ({ agents: [...state.agents, agent] })),
            updateAgent: (id, updates) => {
                set((state) => {
                    // Check if agent exists in current state
                    const exists = state.agents.some(a => a.id === id);

                    if (exists) {
                        // Update existing agent
                        return {
                            agents: state.agents.map((a) => a.id === id ? { ...a, ...updates } : a)
                        };
                    } else {
                        // Agent not in state, find in PRESET_AGENTS and add with updates
                        const presetAgent = PRESET_AGENTS.find(a => a.id === id);
                        if (presetAgent) {
                            return {
                                agents: [...state.agents, { ...presetAgent, ...updates }]
                            };
                        } else {
                            console.warn('[AgentStore] Agent not found:', id);
                            return state;
                        }
                    }
                });
            },
            deleteAgent: (id) => set((state) => ({
                agents: state.agents.filter((a) => a.id !== id)
            })),
            togglePinAgent: (id) => set((state) => ({
                agents: state.agents.map((a) => a.id === id ? { ...a, isPinned: !a.isPinned } : a)
            })),
            getAgent: (id) => {
                const stateAgent = get().agents.find((a) => a.id === id);
                if (stateAgent) return stateAgent;
                // Fallback to presets if not in state (e.g. after adding new preset)
                return PRESET_AGENTS.find((a) => a.id === id);
            },
        }),
        {
            name: 'agent-storage',
            storage: createJSONStorage(() => AsyncStorage),
        }
    )
);
