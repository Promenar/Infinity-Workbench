import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Agent, AgentId } from '../types/chat';

interface AgentState {
    agents: Agent[];
    addAgent: (agent: Agent) => void;
    updateAgent: (id: AgentId, updates: Partial<Agent>) => void;
    deleteAgent: (id: AgentId) => void;
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
        isPreset: true,
        created: Date.now(),
    }
];

export const useAgentStore = create<AgentState>()(
    persist(
        (set, get) => ({
            agents: PRESET_AGENTS,
            addAgent: (agent) => set((state) => ({ agents: [...state.agents, agent] })),
            updateAgent: (id, updates) => set((state) => ({
                agents: state.agents.map((a) => a.id === id ? { ...a, ...updates } : a)
            })),
            deleteAgent: (id) => set((state) => ({
                agents: state.agents.filter((a) => a.id !== id)
            })),
            getAgent: (id) => get().agents.find((a) => a.id === id),
        }),
        {
            name: 'agent-storage',
            storage: createJSONStorage(() => AsyncStorage),
        }
    )
);
