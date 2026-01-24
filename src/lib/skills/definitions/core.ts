
import { z } from 'zod';
import { Skill, SkillContext, SkillResult } from '../../../types/skills';
import { useApiStore } from '../../../store/api-store';
// Removed static import of useChatStore to break require cycle
import { performWebSearch, fetchWebPageContent } from '../../../features/chat/utils/web-search';
import { MemoryManager } from '../../rag/memory-manager';

// Mock implementation helper until we connect to real services in Phase 4

export const QueryVectorDbSkill: Skill = {
    id: 'query_vector_db',
    name: 'Search Knowledge Base',
    description: 'Search for relevant information in the user\'s personal knowledge base and memories. IMPORTANT: If the user mentions "global", "all documents", or "across all sessions", you MUST set scope to "global".',
    schema: z.object({
        query: z.string().min(1).describe('REQUIRED: The search query string. MUST NOT BE EMPTY.'),
        topK: z.number().optional().describe('Number of results (default: 5)'),
        type: z.enum(['memory', 'document', 'all']).optional().describe('Source filter'),
        scope: z.enum(['session', 'global']).optional().describe('Search scope. "session" for current chat/authorized context (default). "global" for the entire knowledge base. Use "global" ONLY when explicitly requested or searching across all possible history.'),
    }),
    execute: async (params, context) => {
        if (!context.sessionId) {
            throw new Error('Session ID is required for vector database queries');
        }

        if (!params.query || params.query.trim().length === 0) {
            throw new Error('Search query was empty. You MUST provide a valid "query" string parameter to search. Please analyze the user request to extract a keyword and TRY AGAIN.');
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

        // 🔑 核心逻辑优化：智能范围决策
        const isSuperAssistant = context.sessionId === 'super_assistant';
        let isGlobal = params.scope === 'global' || sessionRagOptions.isGlobal || globalRagConfig.isGlobal || false;

        // 特权对齐：超级助手默认为全库模式，除非显式指定 session
        if (isSuperAssistant && params.scope !== 'session') {
            isGlobal = true;
        }

        try {
            const { context: contextText, references, metadata } = await MemoryManager.retrieveContext(
                params.query,
                context.sessionId,
                {
                    enableMemory,
                    enableDocs,
                    activeDocIds,
                    activeFolderIds,
                    isGlobal,
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
            const config = apiStore.searchConfig;

            // Call the shared web search utility with full config
            const { context: searchResultText, sources } = await performWebSearch(
                params.query,
                config
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
    description: 'Generate an image using a specialized AI model. ALWAYS use this tool for image creation requests (e.g. "draw a cat"). Do NOT try to write image files manually via filesystem tools.',
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

export const BrowseWebPageSkill: Skill = {
    id: 'browse_web_page',
    name: 'Browse Web Page',
    description: 'Fetch and read the full content of a specific web page. Use this when search snippets are insufficient and you need to dive deeper into a specific URL.',
    schema: z.object({
        url: z.string().url().describe('The URL of the web page to read'),
    }),
    execute: async (params: { url: string }, context) => {
        try {
            const content = await fetchWebPageContent(params.url);

            return {
                id: `browse_${Date.now()}`,
                content: content,
                status: 'success',
                data: {
                    url: params.url
                }
            };
        } catch (e: any) {
            return {
                id: `browse_err_${Date.now()}`,
                content: `Failed to read page: ${e.message}`,
                status: 'error'
            };
        }
    },
};
