
import { z } from 'zod';
import { Skill, SkillContext, SkillResult } from '../../../types/skills';
import { useApiStore } from '../../../store/api-store';
// Removed static import of useChatStore to break require cycle
import { performWebSearch } from '../../../features/chat/utils/web-search';
import { MemoryManager } from '../../rag/memory-manager';

// Mock implementation helper until we connect to real services in Phase 4

export const QueryVectorDbSkill: Skill = {
    id: 'query_vector_db',
    name: 'Search Knowledge Base',
    description: 'Search for relevant information in the user\'s personal knowledge base and memories. Use this when the user asks specific questions about their documents, history, or saved context.',
    schema: z.object({
        query: z.string().describe('The search query string'),
        topK: z.number().optional().describe('Number of results to return (default: 5)'),
        type: z.enum(['memory', 'document', 'all']).optional().describe('Filter by source type'),
    }),
    execute: async (params, context) => {
        if (!context.sessionId) {
            throw new Error('Session ID is required for vector database queries');
        }

        const type = params.type || 'all';
        const enableMemory = type === 'memory' || type === 'all';
        const enableDocs = type === 'document' || type === 'all';

        // 🔑 实时获取当前会话的授权范围
        const { useChatStore } = await import('../../../store/chat-store');
        const chatStore = useChatStore.getState();
        const settingsStore = (await import('../../../store/settings-store')).useSettingsStore.getState();
        const session = chatStore.getSession(context.sessionId);

        // Merge global config with session options
        const globalRagConfig = settingsStore.globalRagConfig;
        const sessionRagOptions = session?.ragOptions || {};

        const activeDocIds = sessionRagOptions.activeDocIds ?? globalRagConfig.activeDocIds ?? [];
        const activeFolderIds = sessionRagOptions.activeFolderIds ?? globalRagConfig.activeFolderIds ?? [];

        // Logical OR for isGlobal: if either session or global says true, then true.
        // Default to false if both undefined.
        const isGlobal = sessionRagOptions.isGlobal ?? globalRagConfig.isGlobal ?? false;

        try {
            const { context: contextText, references, metadata } = await MemoryManager.retrieveContext(
                params.query,
                context.sessionId,
                {
                    enableMemory,
                    enableDocs,
                    activeDocIds,
                    activeFolderIds,
                    isGlobal, // ✅ Dynamic isGlobal
                    ragConfig: {
                        memoryLimit: params.topK || 5,
                        docLimit: params.topK || 5,
                    } as any
                }
            );

            return {
                id: `qdb_${Date.now()}`,
                content: contextText || 'No relevant information found.',
                status: 'success',
                data: {
                    references,
                    metadata
                }
            };
        } catch (e: any) {
            return {
                id: `qdb_err_${Date.now()}`,
                content: `Vector DB query failed: ${e.message}`,
                status: 'error'
            };
        }
    },
};

export const SaveCoreMemorySkill: Skill = {
    id: 'save_core_memory',
    name: 'Save Core Memory',
    description: 'Save important facts, preferences, or context about the user for long-term memory. ONLY use this when the user explicitly asks to remember something or for critical information that should persist across sessions.',
    schema: z.object({
        content: z.string().describe('The fact or information to remember'),
        category: z.enum(['preference', 'fact', 'context']).optional().describe('Category of the memory'),
    }),
    execute: async (params, context) => {
        if (!context.sessionId) {
            throw new Error('Session ID is required to save core memory');
        }

        try {
            const contentToSave = params.category
                ? `[${params.category.toUpperCase()}] ${params.content}`
                : params.content;

            const memId = `mem_core_${Date.now()}`;

            await MemoryManager.upsertMemory({
                id: memId,
                content: contentToSave,
                sessionId: context.sessionId,
                type: 'memory',
                role: 'system',
                createdAt: Date.now()
            });

            return {
                id: memId,
                content: `Successfully saved core memory: "${params.content}"`,
                status: 'success'
            };
        } catch (e: any) {
            return {
                id: `save_err_${Date.now()}`,
                content: `Failed to save core memory: ${e.message}`,
                status: 'error'
            };
        }
    },
};

export const SearchInternetSkill: Skill = {
    id: 'search_internet',
    name: 'Search Internet',
    description: 'Search the live internet for up-to-date information, news, or specific data not present in the knowledge base.',
    schema: z.object({
        query: z.string().describe('The search query'),
    }),
    execute: async (params: { query: string }, context) => {
        try {
            const apiStore = useApiStore.getState();
            const config = apiStore.googleSearchConfig;

            // Call the shared web search utility
            const { context: searchResultText, sources } = await performWebSearch(
                params.query,
                config?.apiKey,
                config?.cx
            );

            return {
                id: `search_${Date.now()}`,
                content: searchResultText,
                status: 'success',
                data: {
                    sources: sources
                }
            };
        } catch (e: any) {
            return {
                id: `search_err_${Date.now()}`,
                content: `Search failed: ${e.message}`,
                status: 'error'
            };
        }
    },
};

export const GenerateImageSkill: Skill = {
    id: 'generate_image',
    name: 'Generate Image',
    description: 'Generate an image based on a text prompt. Use this when the user explicitly asks to draw or generate a picture.',
    schema: z.object({
        prompt: z.string().describe('The image description prompt'),
        style: z.string().optional().describe('Art style (e.g., realistic, anime, oil painting)'),
    }),
    execute: async (params: { prompt: string; style?: string }, context) => {
        const { imageGenerationService } = require('../../services/image-generation');
        try {
            const result = await imageGenerationService.generateImage(params.prompt, { style: params.style });
            return {
                id: `img_${Date.now()}`,
                content: `Image generated successfully: ${result.url}`,
                status: 'success',
                data: {
                    url: result.url,
                    revisedPrompt: result.revisedPrompt
                }
            };
        } catch (e: any) {
            return {
                id: `img_err_${Date.now()}`,
                content: `Image generation failed: ${e.message}`,
                status: 'error'
            };
        }
    },
};
