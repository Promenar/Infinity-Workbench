import { Zap, BookOpen, Code, LucideIcon } from 'lucide-react-native';
import { Colors } from '../../theme/colors';

export interface RagPresetConfig {
    contextWindow: number;
    summaryThreshold: number;
    memoryLimit: number;
    memoryThreshold: number;
    docLimit: number;
    docThreshold: number;
    docChunkSize: number;
    chunkOverlap: number;
    memoryChunkSize: number;
}

export interface RagPreset {
    name: string; // Key for i18n
    icon: LucideIcon;
    color: string;
    config: RagPresetConfig;
}

export const RAG_PRESETS: Record<string, RagPreset> = {
    balanced: {
        name: 'rag.presetBalanced',
        icon: Zap,
        color: '#06b6d4', // Cyan-500 (Not in standard theme yet)
        config: {
            docChunkSize: 800,
            chunkOverlap: 100,
            memoryChunkSize: 1000,
            contextWindow: 20,
            summaryThreshold: 10,
            memoryLimit: 5,
            memoryThreshold: 0.7,
            docLimit: 8,
            docThreshold: 0.45,
        } as any, // Relax typing for hybrid config
    },
    writing: {
        name: 'rag.presetWriting',
        icon: BookOpen,
        color: Colors.warning, // Amber
        config: {
            docChunkSize: 1200,
            chunkOverlap: 200,
            memoryChunkSize: 1500,
            contextWindow: 30,
            summaryThreshold: 15,
            memoryLimit: 7,
            memoryThreshold: 0.75,
            docLimit: 10,
            docThreshold: 0.5,
        } as any,
    },
    coding: {
        name: 'rag.presetCode',
        icon: Code,
        color: Colors.primary, // Indigo
        config: {
            docChunkSize: 600,
            chunkOverlap: 50,
            memoryChunkSize: 800,
            contextWindow: 15,
            summaryThreshold: 8,
            memoryLimit: 4,
            memoryThreshold: 0.65,
            docLimit: 6,
            docThreshold: 0.4,
        } as any,
    },
};

export const RAG_DEFAULTS = {
    slider: {
        contextWindow: { min: 10, max: 50, step: 5 },
        summaryThreshold: { min: 5, max: 30, step: 5 },
    },
};
