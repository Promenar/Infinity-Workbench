import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { produce } from 'immer';

export type ApiProvider = 'openai' | 'anthropic' | 'deepseek' | 'local';

export interface ModelConfig {
    id: string;
    provider: ApiProvider;
    modelName: string;
    apiKey: string;
    baseUrl?: string;
    temperature: number;
    systemPrompt: string;
}

interface ApiState {
    groups: {
        daily: ModelConfig;
        writer: ModelConfig;
    };
    activeGroup: 'daily' | 'writer';
    updateConfig: (group: 'daily' | 'writer', updates: Partial<ModelConfig>) => void;
    setActiveGroup: (group: 'daily' | 'writer') => void;
}

const defaultConfig: ModelConfig = {
    id: 'default',
    provider: 'openai',
    modelName: 'gpt-4o',
    apiKey: '',
    temperature: 0.7,
    systemPrompt: 'You are a helpful AI assistant.',
};

export const useApiStore = create<ApiState>()(
    persist(
        (set) => ({
            groups: {
                daily: { ...defaultConfig, id: 'daily-default', systemPrompt: 'Be concise and helpful.' },
                writer: { ...defaultConfig, id: 'writer-default', temperature: 0.9, systemPrompt: 'You are a creative novelist.' },
            },
            activeGroup: 'daily',
            updateConfig: (group, updates) =>
                set(
                    produce((state: ApiState) => {
                        state.groups[group] = { ...state.groups[group], ...updates };
                    })
                ),
            setActiveGroup: (group) => set({ activeGroup: group }),
        }),
        {
            name: 'api-storage',
            storage: createJSONStorage(() => AsyncStorage),
        }
    )
);
