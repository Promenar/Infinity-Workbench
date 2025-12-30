import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Session, SessionId, AgentId, Message, TokenUsage, InferenceParams, GeneratedImageData, RagReference } from '../types/chat';
import { db } from '../lib/db';
import { useAgentStore } from './agent-store';
import { useApiStore } from './api-store';
import { createLlmClient } from '../lib/llm/factory';
import { estimateTokens } from '../features/chat/utils/token-counter';
import { performWebSearch } from '../features/chat/utils/web-search';
import { LlmClient } from '../lib/llm/types';
import * as FileSystem from 'expo-file-system/legacy';
import { MemoryManager } from '../lib/rag/memory-manager';

import { ContextManager } from '../features/chat/utils/ContextManager';

// ✅ 辅助函数：从数据库查询消息归档状态
const enrichMessagesWithArchiveStatus = async (sessionId: string, messages: Message[]): Promise<Message[]> => {
    try {
        // 查询该会话所有已归档的消息ID
        const result = await db.execute(
            'SELECT DISTINCT start_message_id, end_message_id FROM vectors WHERE session_id = ?',
            [sessionId]
        );

        const archivedMessageIds = new Set<string>();
        if (result.rows) {
            const rows = (result.rows as any)._array || (result.rows as any) || [];
            for (const row of rows) {
                if (row.start_message_id) archivedMessageIds.add(row.start_message_id);
                if (row.end_message_id) archivedMessageIds.add(row.end_message_id);
            }
        }

        // 标记消息是否已归档
        return messages.map(msg => ({
            ...msg,
            isArchived: archivedMessageIds.has(msg.id)
        }));
    } catch (e) {
        console.error('[ChatStore] Failed to enrich messages with archive status:', e);
        return messages;
    }
};

interface ChatState {
    sessions: Session[];
    activeRequests: Record<string, LlmClient>; // sessionId -> activeClient
    currentGeneratingSessionId: string | null;

    addSession: (session: Session) => void;
    updateSession: (id: SessionId, updates: Partial<Session>) => void;
    deleteSession: (id: SessionId) => void;
    addMessage: (sessionId: SessionId, message: Message) => void;
    getSessionsByAgent: (agentId: AgentId) => Session[];
    getSession: (id: SessionId) => Session | undefined;

    // Actions
    // Actions
    generateMessage: (sessionId: SessionId, content: string, options?: {
        webSearch?: boolean;
        reasoning?: boolean;
        images?: (string | GeneratedImageData)[];
        ragOptions?: {
            enableMemory?: boolean;
            enableDocs?: boolean;
            activeDocIds?: string[];
            activeFolderIds?: string[]; // ✅ 添加缺失的字段
            isGlobal?: boolean;
        }
    }) => Promise<void>;
    generateSessionTitle: (sessionId: SessionId) => Promise<string | undefined>;
    abortGeneration: (sessionId: SessionId) => void;

    // Specialized update methods
    updateSessionTitle: (id: SessionId, title: string) => void;
    updateSessionPrompt: (id: SessionId, prompt: string | undefined) => void;
    updateSessionModel: (id: SessionId, modelId: string | undefined) => void;
    updateSessionOptions: (id: SessionId, options: {
        webSearch?: boolean;
        reasoning?: boolean;
        ragOptions?: {
            enableMemory?: boolean;
            enableDocs?: boolean;
            activeDocIds?: string[]; // Allow updating activeDocIds
            activeFolderIds?: string[];
            isGlobal?: boolean;
        };
    }) => void;
    updateSessionScrollOffset: (id: SessionId, offset: number) => void;
    updateMessageContent: (sessionId: SessionId, messageId: string, content: string, tokens?: TokenUsage, reasoning?: string, citations?: { title: string; url: string; source?: string }[], ragReferences?: RagReference[], ragReferencesLoading?: boolean) => void;
    updateSessionInferenceParams: (id: SessionId, params: InferenceParams) => void;
    deleteMessage: (sessionId: SessionId, messageId: string) => void;
    toggleSessionPin: (sessionId: SessionId) => void;

    updateSessionDraft: (sessionId: SessionId, draft: string | undefined) => void;
    setMessagesArchived: (sessionId: SessionId, messageIds: string[]) => void;
}

export const useChatStore = create<ChatState>()(
    persist(
        (set, get) => ({
            sessions: [],
            activeRequests: {},
            currentGeneratingSessionId: null,

            addSession: (session) => set((state) => ({ sessions: [session, ...state.sessions] })),
            updateSession: (id, updates) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, ...updates } : s)
            })),
            deleteSession: (id) => set((state) => ({
                sessions: state.sessions.filter((s) => s.id !== id)
            })),
            addMessage: (sessionId, message) => set((state) => ({
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        return {
                            ...s,
                            messages: [...s.messages, message],
                            lastMessage: message.content,
                            unread: (s.unread || 0),
                            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                        };
                    }
                    return s;
                })
            })),
            getSessionsByAgent: (agentId) => {
                const sessions = get().sessions.filter((s) => s.agentId === agentId);
                return sessions.sort((a, b) => {
                    if (a.isPinned === b.isPinned) return 0;
                    return a.isPinned ? -1 : 1;
                });
            },
            getSession: (id) => {
                const session = get().sessions.find((s) => s.id === id);
                // Note: 无法在同步getter中调用异步enrichMessagesWithArchiveStatus
                // 归档状态将在组件层面按需查询
                return session;
            },

            toggleSessionPin: (id) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, isPinned: !s.isPinned } : s)
            })),

            updateSessionTitle: (id, title) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, title } : s)
            })),
            updateSessionPrompt: (id, prompt) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, customPrompt: prompt } : s)
            })),
            updateSessionModel: (id, modelId) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, modelId } : s)
            })),
            updateSessionOptions: (id, options) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? {
                    ...s,
                    options: { ...s.options, ...options },
                    ragOptions: { ...s.ragOptions, ...options?.ragOptions } // Merge if passed
                } : s)
            })),
            updateSessionScrollOffset: (id, offset) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, scrollOffset: offset } : s)
            })),
            updateMessageContent: (sessionId: SessionId, messageId: string, content: string, tokens?: TokenUsage, reasoning?: string, citations?: { title: string; url: string; source?: string }[], ragReferences?: RagReference[], ragReferencesLoading?: boolean) => set((state) => ({
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        return {
                            ...s,
                            messages: s.messages.map((m) =>
                                m.id === messageId ? {
                                    ...m,
                                    content,
                                    tokens: tokens || m.tokens,
                                    reasoning: reasoning !== undefined ? reasoning : m.reasoning,
                                    citations: citations !== undefined ? citations : m.citations,
                                    ragReferences: ragReferences !== undefined ? ragReferences : m.ragReferences,
                                    ragReferencesLoading: ragReferencesLoading !== undefined ? ragReferencesLoading : m.ragReferencesLoading
                                } : m
                            ),
                            lastMessage: content,
                        };
                    }
                    return s;
                })
            })),

            updateSessionInferenceParams: (id, params) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, inferenceParams: { ...s.inferenceParams, ...params } } : s)
            })),

            updateSessionDraft: (id, draft) => set((state) => ({
                sessions: state.sessions.map((s) => s.id === id ? { ...s, draft } : s)
            })),

            deleteMessage: (sessionId, messageId) => {
                const state = get();
                // If the session being edited is currently generating
                if (state.currentGeneratingSessionId === sessionId) {
                    const session = state.sessions.find(s => s.id === sessionId);
                    if (session) {
                        // If we are deleting the last message (which is usually the AI message under generation)
                        const lastMsg = session.messages[session.messages.length - 1];
                        if (lastMsg && lastMsg.id === messageId) {
                            console.log('[ChatStore] Deleting active generating message, aborting...');
                            state.abortGeneration(sessionId);
                        }
                    }
                }

                set((state) => ({
                    sessions: state.sessions.map((s) => {
                        if (s.id === sessionId) {
                            return {
                                ...s,
                                messages: s.messages.filter((m) => m.id !== messageId),
                            };
                        }
                        return s;
                    })
                }));
            },

            setMessagesArchived: (sessionId, messageIds) => set((state) => ({
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        const idSet = new Set(messageIds);
                        return {
                            ...s,
                            messages: s.messages.map(m => idSet.has(m.id) ? { ...m, isArchived: true } : m)
                        };
                    }
                    return s;
                })
            })),

            abortGeneration: (sessionId) => {
                const client = get().activeRequests[sessionId];
                if (client) {
                    client.abort?.();
                    set((state) => {
                        const newRequests = { ...state.activeRequests };
                        delete newRequests[sessionId];
                        const isCurrent = state.currentGeneratingSessionId === sessionId;
                        return {
                            activeRequests: newRequests,
                            currentGeneratingSessionId: isCurrent ? null : state.currentGeneratingSessionId
                        };
                    });
                }
            },

            generateMessage: async (sessionId, content, options) => {
                const session = get().getSession(sessionId);
                if (!session) return;

                const agentStore = useAgentStore.getState();
                const apiStore = useApiStore.getState();

                const agent = agentStore.getAgent(session.agentId);
                if (!agent) {
                    console.error('Agent not found');
                    return;
                }

                // 1. Resolve Model Configuration
                const modelId = session.modelId || agent.defaultModel;
                let provider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.uuid === modelId));
                let modelConfig = provider?.models.find(m => m.uuid === modelId);

                if (!provider) {
                    provider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.id === modelId));
                    modelConfig = provider?.models.find(m => m.id === modelId);
                }

                if (!provider || !modelConfig) {
                    console.error('No enabled provider found for model:', modelId);
                    return;
                }

                // Check for multimodal capability
                if (options?.images && options.images.length > 0) {
                    if (!modelConfig.capabilities.vision) {
                        const errorMsg = `Model "${modelConfig.name}" does not support image input. Please switch to a multimodal model (e.g. GPT-4o, Gemini Pro Vision).`;

                        // Add ephemeral error message to chat
                        get().addMessage(sessionId, {
                            id: `err_${Date.now()}`,
                            role: 'assistant',
                            content: `[System Error] ${errorMsg}`,
                            timestamp: Date.now(),
                        });
                        return;
                    }
                }

                // 2. Add User Message
                const promptTokens = estimateTokens(content);

                // Normalize images
                const normalizedImages: GeneratedImageData[] | undefined = options?.images?.map(img => {
                    if (typeof img === 'string') {
                        return { thumbnail: img, original: img, mime: 'image/jpeg' };
                    }
                    return img;
                });

                const userMsg: Message = {
                    id: `msg_${Date.now()}`,
                    role: 'user',
                    content,
                    timestamp: Date.now(),
                    tokens: { input: promptTokens, output: 0, total: promptTokens },
                    images: normalizedImages
                };
                get().addMessage(sessionId, userMsg);

                set({ currentGeneratingSessionId: sessionId });

                // 3. Add Assistant Placeholder
                const assistantMsgId = `msg_ai_${Date.now()}`;
                const assistantMsg: Message = {
                    id: assistantMsgId,
                    role: 'assistant',
                    content: '',
                    timestamp: Date.now(),
                    modelId: modelId,
                    ragReferences: [] // Initialize RAG references
                };
                get().addMessage(sessionId, assistantMsg);

                let accumulatedContent = '';
                let accumulatedReasoning = '';
                let accumulatedCitations: { title: string; url: string; source?: string }[] | undefined = undefined; // Initialize correctly
                let ragReferences: RagReference[] = []; // Track RAG references safely

                try {
                    const extendedConfig = {
                        ...modelConfig,
                        provider: provider.type,
                        apiKey: provider.apiKey,
                        baseUrl: provider.baseUrl,
                        temperature: agent.params?.temperature ?? 0.7,
                        vertexProject: provider.vertexProject,
                        vertexLocation: provider.vertexLocation,
                        vertexKeyJson: provider.vertexKeyJson
                    };

                    const client = createLlmClient(extendedConfig as any);
                    set((state) => ({ activeRequests: { ...state.activeRequests, [sessionId]: client } }));

                    // Determine if we need client-side search (Non-native models)
                    // Native models: gemini-*, vertex-* (usually handled by provider)
                    // We assume Gemini/Vertex providers handle 'webSearch' option natively via tools
                    // For others (DeepSeek/OpenAI), we do client-side search
                    // Determine if we need client-side search (Non-native models)
                    // Native models: gemini (gemini), google (vertex)
                    // We assume Gemini/Vertex providers handle 'webSearch' option natively via tools
                    // For others (DeepSeek/OpenAI), we do client-side search
                    const isNativeSearch = provider.type === 'gemini' || provider.type === 'google';
                    let searchContext = '';
                    let initialCitations: { title: string; url: string; source?: string }[] = [];

                    if (options?.webSearch && !isNativeSearch) {
                        try {
                            let apiKey = apiStore.googleSearchConfig?.apiKey;
                            let cx = apiStore.googleSearchConfig?.cx;

                            get().updateMessageContent(sessionId, assistantMsgId, 'Searching the web...', undefined, undefined, undefined);

                            const searchResult = await performWebSearch(content, apiKey, cx);
                            searchContext = searchResult.context;
                            initialCitations = searchResult.sources.map((s: any) => ({ title: s.title, url: s.url, source: s.source }));


                            // Pre-fill citations so UI shows them immediately
                            accumulatedCitations = initialCitations;
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, initialCitations);
                        } catch (err) {
                            console.error('Client-side search failed', err);
                        }
                    }

                    // 3.5 RAG 检索 (Retrieval)
                    let ragContext = '';

                    // 🔑 关键修复：优先从session持久化配置读取，再考虑临时覆盖
                    const sessionRagOptions = session.ragOptions || {};
                    const tempRagOptions = options?.ragOptions || {};

                    // 合并配置：临时参数可覆盖session配置（保持灵活性）
                    const finalRagOptions = {
                        enableMemory: tempRagOptions.enableMemory ?? sessionRagOptions.enableMemory ?? true,
                        enableDocs: tempRagOptions.enableDocs ?? sessionRagOptions.enableDocs ?? false,
                        activeDocIds: tempRagOptions.activeDocIds ?? sessionRagOptions.activeDocIds ?? [],
                        activeFolderIds: tempRagOptions.activeFolderIds ?? sessionRagOptions.activeFolderIds ?? [],
                        isGlobal: tempRagOptions.isGlobal ?? sessionRagOptions.isGlobal ?? false
                    };

                    const isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs;
                    const apiMessage = { content, images: normalizedImages };

                    if (isRagEnabled) {
                        try {
                            // 🐛 调试日志：记录授权信息
                            console.log('[RAG DEBUG] 开始检索:', {
                                sessionId,
                                enableMemory: finalRagOptions.enableMemory,
                                enableDocs: finalRagOptions.enableDocs,
                                activeDocIds: finalRagOptions.activeDocIds,
                                activeFolderIds: finalRagOptions.activeFolderIds,
                                isGlobal: finalRagOptions.isGlobal,
                                docCount: finalRagOptions.activeDocIds?.length || 0,
                                folderCount: finalRagOptions.activeFolderIds?.length || 0
                            });

                            // Set loading state
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, [], true);

                            // For Super Assistant, force global search
                            const effectiveRagOptions = {
                                ...finalRagOptions,
                                isGlobal: sessionId === 'super_assistant' ? true : finalRagOptions.isGlobal,
                                ragConfig: agent.ragConfig // ✅ 关键：传入特定助手的 RAG 配置
                            };

                            const { context: retrievedContext, references } = await MemoryManager.retrieveContext(
                                apiMessage.content,
                                sessionId,
                                effectiveRagOptions
                            );

                            ragContext = retrievedContext;
                            ragReferences = references;

                            // 🐛 调试日志：记录检索结果
                            console.log('[RAG DEBUG] 检索完成:', {
                                contextLength: ragContext.length,
                                referencesCount: ragReferences.length,
                                memoryRefs: ragReferences.filter(r => r.type === 'memory').length,
                                docRefs: ragReferences.filter(r => r.type === 'doc').length,
                                docIds: ragReferences.filter(r => r.type === 'doc').map(r => r.docId)
                            });

                            // Update with results and turn off loading
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, ragReferences, false);
                        } catch (e) {
                            console.error('RAG Retrieval failed:', e);
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, [], false);
                        }
                    } else {
                        console.log('[RAG DEBUG] RAG已禁用，跳过检索');
                    }

                    // 4. 准备上下文 (Prepare Context)
                    // 将 RAG 上下文注入到系统提示词中
                    let finalSystemPrompt = agent.systemPrompt + (session.customPrompt ? `\n\n${session.customPrompt}` : '');
                    if (ragContext) {
                        finalSystemPrompt += `\n\n${ragContext}`;
                    }

                    let contextMsgs = [];
                    // 将搜索上下文 (Web) 注入到系统提示词或作为单独的系统消息
                    if (searchContext) {
                        contextMsgs.push({ role: 'system', content: finalSystemPrompt + '\n\n' + searchContext });
                    } else {
                        contextMsgs.push({ role: 'system', content: finalSystemPrompt });
                    }

                    // Helper to format content for LLM (Text or Multimodal)
                    const formatContent = async (msgContent: string, images?: GeneratedImageData[], isHistory?: boolean) => {
                        // Priority 1: If model doesn't support vision, skip image processing entirely
                        const hasVision = modelConfig?.capabilities.vision;
                        if (!images || images.length === 0 || !hasVision) return msgContent;

                        // Priority 2: In history, we might want to skip or limit high-res images to save bandwidth/lag
                        // But for now, just skip if it's the current model is not vision-capable
                        const parts: any[] = [{ type: 'text', text: msgContent }];
                        for (const img of images) {
                            try {
                                // Optimization: Only read original if it's reasonably small or if necessary
                                const imgPath = img.original;
                                const base64 = await FileSystem.readAsStringAsync(imgPath, { encoding: 'base64' });
                                parts.push({
                                    type: 'image_url',
                                    image_url: { url: `data:${img.mime || 'image/jpeg'};base64,${base64}` }
                                });
                            } catch (e) {
                                console.error('Failed to read image:', img.original, e);
                            }
                        }
                        return parts;
                    };

                    // 🔑 动态上下文窗口大小
                    const activeWindowSize = agent.ragConfig?.contextWindow || 10;

                    const history = await Promise.all(session.messages.slice(-activeWindowSize).map(async (m: Message) => ({
                        role: m.role,
                        content: await formatContent(m.content, m.images, true)
                    })));

                    contextMsgs = [
                        ...contextMsgs,
                        ...history,
                        { role: 'user', content: await formatContent(content, normalizedImages) }
                    ] as any;

                    const context = ContextManager.trimContext(contextMsgs, activeWindowSize);
                    // For token estimation, we only count text parts
                    const contextText = context.map((m: any) => {
                        if (typeof m.content === 'string') return m.content;
                        return (m.content as any[]).map((p: any) => p.type === 'text' ? p.text : '').join('\n');
                    }).join('\n');
                    const totalContextTokens = estimateTokens(contextText);

                    // 5. Stream Chat with optimized batching
                    let updateTimer: NodeJS.Timeout | null = null;
                    let lastTokenEstimateLength = 0;
                    let accumulatedUsage: { input: number; output: number; total: number } | undefined;

                    const scheduleUpdate = () => {
                        if (updateTimer) return; // 防止重复调度
                        updateTimer = setTimeout(() => {
                            updateTimer = null;

                            let inputTokens = totalContextTokens;
                            let completionTokens = 0;

                            if (accumulatedUsage) {
                                // Prefer API usage if available
                                inputTokens = accumulatedUsage.input;
                                completionTokens = accumulatedUsage.output;
                            } else {
                                // Fallback to estimation
                                const shouldEstimateTokens = accumulatedContent.length - lastTokenEstimateLength > 500;
                                completionTokens = shouldEstimateTokens
                                    ? estimateTokens(accumulatedContent)
                                    : Math.floor(accumulatedContent.length / 4);

                                if (shouldEstimateTokens) {
                                    lastTokenEstimateLength = accumulatedContent.length;
                                }
                            }

                            get().updateMessageContent(sessionId, assistantMsgId, accumulatedContent, {
                                input: inputTokens,
                                output: completionTokens,
                                total: inputTokens + completionTokens
                            }, accumulatedReasoning, accumulatedCitations);
                        }, 100); // 每 100ms 最多更新一次，减少渲染压力
                    };

                    await client.streamChat(
                        context,
                        (chunk) => {
                            // Handle object chunk (new) or string chunk (legacy safety)
                            if (typeof chunk === 'string') {
                                accumulatedContent += chunk;
                            } else {
                                if (chunk.content) accumulatedContent += chunk.content;
                                if (chunk.reasoning) accumulatedReasoning += chunk.reasoning;
                                if (chunk.citations) accumulatedCitations = chunk.citations; // Native citations (replace/update)
                                if (chunk.usage) accumulatedUsage = chunk.usage;
                            }
                            scheduleUpdate();
                        },
                        (error) => { throw error; },
                        options // Pass options including webSearch
                    );

                    // Final Update
                    let finalInputTokens = totalContextTokens;
                    let finalCompletionTokens = estimateTokens(accumulatedContent);

                    if (accumulatedUsage) {
                        finalInputTokens = accumulatedUsage.input;
                        finalCompletionTokens = accumulatedUsage.output;
                    }

                    get().updateMessageContent(sessionId, assistantMsgId, accumulatedContent, {
                        input: finalInputTokens,
                        output: finalCompletionTokens,
                        total: finalInputTokens + finalCompletionTokens
                    }, accumulatedReasoning, accumulatedCitations, ragReferences);

                    // 🔑 关键修复1: 归档本轮对话到向量记忆
                    // ✅ 仅当enableMemory开启时才归档
                    if (finalRagOptions.enableMemory !== false) {
                        try {
                            const archiveStartTime = Date.now();

                            // 🎯 启动归档状态
                            const { updateProcessingState } = await import('../store/rag-store').then(m => m.useRagStore.getState());
                            updateProcessingState({
                                sessionId,
                                status: 'chunking',
                                startTime: archiveStartTime,
                                chunks: []
                            }, assistantMsgId);

                            // ✅ 关键修复：让React先渲染loading状态
                            await new Promise(resolve => setTimeout(resolve, 0));

                            await MemoryManager.addTurnToMemory(
                                sessionId,
                                content,  // userMsg.content
                                accumulatedContent,  // assistantMsg.content
                                userMsg.id,
                                assistantMsgId
                            );

                            // ✅ 确保loading状态至少显示800ms
                            const elapsed = Date.now() - archiveStartTime;
                            if (elapsed < 800) {
                                await new Promise(resolve => setTimeout(resolve, 800 - elapsed));
                            }

                            // 🎯 归档完成（添加切片数量信息）
                            const estimatedChunks = Math.ceil((content.length + accumulatedContent.length) / 500);
                            updateProcessingState({
                                sessionId,
                                status: 'archived',
                                chunks: []
                            }, assistantMsgId);

                            // ✅ 关键修复3：更新Store中的消息状态，通知UI显示绿勾
                            get().setMessagesArchived(sessionId, [userMsg.id, assistantMsgId]);

                            console.log('[RAG] 对话已归档到向量库');
                        } catch (e) {
                            console.error('[RAG] 记忆归档失败:', e);
                            // 错误状态
                            const { updateProcessingState } = await import('../store/rag-store').then(m => m.useRagStore.getState());
                            updateProcessingState({
                                sessionId,
                                status: 'error'
                            }, assistantMsgId);
                        }
                    }

                    // 🔑 关键修复2: 检查并触发自动摘要
                    try {
                        const currentMessages = get().getSession(sessionId)?.messages || [];

                        // ✅ 修复：只计算非system消息
                        const contentMessages = currentMessages.filter(m => m.role !== 'system');
                        const contentMessageCount = contentMessages.length;

                        // ✅ 关键修复：查询所有摘要覆盖的消息ID范围
                        const summariesResult = await db.execute(
                            'SELECT start_message_id, end_message_id FROM context_summaries WHERE session_id = ?',
                            [sessionId]
                        );

                        // 构建已被摘要覆盖的消息ID集合
                        const summarizedMessageIds = new Set<string>();
                        if (summariesResult.rows) {
                            const rows = (summariesResult.rows as any)._array ||
                                (summariesResult.rows as any) ||
                                [];

                            for (const row of rows) {
                                if (row.start_message_id && row.end_message_id) {
                                    // 找到起始和结束消息的索引
                                    const startIdx = contentMessages.findIndex(m => m.id === row.start_message_id);
                                    const endIdx = contentMessages.findIndex(m => m.id === row.end_message_id);

                                    // 将这个范围内的所有消息ID加入集合
                                    if (startIdx !== -1 && endIdx !== -1) {
                                        for (let i = startIdx; i <= endIdx; i++) {
                                            summarizedMessageIds.add(contentMessages[i].id);
                                        }
                                    }
                                }
                            }
                        }

                        // 计算未被摘要的消息数
                        const newMessagesCount = contentMessages.filter(m => !summarizedMessageIds.has(m.id)).length;

                        // 活跃窗口大小（对应ContextManager的finalConfig.maxMessages）
                        const activeWindowSize = agent.ragConfig?.contextWindow || 10;
                        const summaryThreshold = agent.ragConfig?.summaryThreshold || 20;

                        // ✅ 关键修复：只有当new消息数 > (活跃窗口 + 阈值) 时才触发
                        // 因为ContextManager会保留最后activeWindowSize条消息，只摘要超出部分
                        const needsSummary = newMessagesCount > (activeWindowSize + summaryThreshold);

                        console.log(`[ChatStore] 摘要检查: total=${contentMessageCount}, summarized=${summarizedMessageIds.size}, new=${newMessagesCount}, activeWindow=${activeWindowSize}, threshold=${summaryThreshold}, needsSummary=${needsSummary}`);
                        if (needsSummary) {
                            const summaryStartTime = Date.now();

                            // 🎯 启动摘要状态
                            const { updateProcessingState } = await import('../store/rag-store').then(m => m.useRagStore.getState());
                            updateProcessingState({
                                sessionId,
                                status: 'summarizing',
                                startTime: summaryStartTime
                            }, assistantMsgId);

                            // ✅ 关键修复：让React先渲染loading状态
                            await new Promise(resolve => setTimeout(resolve, 0));
                            // 查询摘要前的数量
                            const beforeCount = await db.execute(
                                'SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?',
                                [sessionId]
                            );
                            const beforeSummaryCount = (beforeCount.rows as any)?._array?.[0]?.count ||
                                (beforeCount.rows as any)?.[0]?.count ||
                                ((beforeCount.rows as any).item ? (beforeCount.rows as any).item(0)?.count : 0) || 0;

                            await ContextManager.checkAndSummarize(sessionId, currentMessages, agent);

                            // ✅ 关键修复：确保loading状态至少显示800ms
                            const elapsed = Date.now() - summaryStartTime;
                            if (elapsed < 800) {
                                await new Promise(resolve => setTimeout(resolve, 800 - elapsed));
                            }

                            // 查询摘要后的数量
                            const afterCount = await db.execute(
                                'SELECT COUNT(*) as count FROM context_summaries WHERE session_id = ?',
                                [sessionId]
                            );
                            const afterSummaryCount = (afterCount.rows as any)?._array?.[0]?.count ||
                                (afterCount.rows as any)?.[0]?.count ||
                                ((afterCount.rows as any).item ? (afterCount.rows as any).item(0)?.count : 0) || 0;

                            console.log(`[ChatStore] 摘要前后对比: before=${beforeSummaryCount}, after=${afterSummaryCount}`);

                            // 🎯 只有在真正生成了新摘要时才标记为summarized
                            if (afterSummaryCount > beforeSummaryCount) {
                                updateProcessingState({
                                    sessionId,
                                    status: 'summarized',
                                    summary: ''
                                }, assistantMsgId);
                                console.log('[ChatStore] ✅ 摘要已生成');
                            } else {
                                updateProcessingState({
                                    sessionId,
                                    status: 'idle'
                                }, assistantMsgId);
                                console.log('[ChatStore] ℹ️ 未达到摘要条件，跳过');
                            }
                        }

                        console.log('[RAG] 摘要检查完成');
                    } catch (e) {
                        console.error('[RAG] 摘要生成失败:', e);
                    }

                    // Update Session Stats & Title
                    const sessionAllText = (get().getSession(sessionId)?.messages || []).map(m => m.content).join('\n');
                    get().updateSession(sessionId, {
                        stats: { totalTokens: estimateTokens(sessionAllText) }
                    });

                    if (session.messages.length <= 1 || session.title === agent.name || session.title === 'New Conversation') {
                        get().updateSessionTitle(sessionId, content.substring(0, 30) + (content.length > 30 ? '...' : ''));
                    }

                } catch (error) {
                    if ((error as any).name === 'AbortError' || (error as Error).message.includes('abort')) {
                        console.log('Stream aborted');
                    } else {
                        console.error('Chat error:', error);
                        const errorMsg = (error as Error).message;
                        get().updateMessageContent(sessionId, assistantMsgId, accumulatedContent + `\n\n[Error: ${errorMsg}]`);
                    }
                } finally {
                    set((state) => {
                        const newRequests = { ...state.activeRequests };
                        delete newRequests[sessionId];
                        return { activeRequests: newRequests, currentGeneratingSessionId: null };
                    });
                }
            },

            generateSessionTitle: async (sessionId) => {
                const session = get().getSession(sessionId);
                if (!session || session.messages.length === 0) return undefined;

                const agentStore = useAgentStore.getState();
                const apiStore = useApiStore.getState();
                const agent = agentStore.getAgent(session.agentId);
                const modelId = session.modelId || agent?.defaultModel;

                if (!modelId) return undefined;

                let provider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.uuid === modelId));
                let modelConfig = provider?.models.find(m => m.uuid === modelId);

                if (!provider) {
                    provider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.id === modelId));
                    modelConfig = provider?.models.find(m => m.id === modelId);
                }

                if (!provider || !modelConfig) return undefined;

                try {
                    const extendedConfig = {
                        ...modelConfig,
                        provider: provider.type,
                        apiKey: provider.apiKey,
                        baseUrl: provider.baseUrl,
                        vertexProject: provider.vertexProject,
                        vertexLocation: provider.vertexLocation,
                        vertexKeyJson: provider.vertexKeyJson
                    };

                    const client = createLlmClient(extendedConfig as any);

                    // 提取最近的 4 条消息作为上下文
                    const recentMessages = session.messages.slice(-4).map(m => `${m.role}: ${m.content}`).join('\n');
                    const prompt = `Based on the conversation content, summarize a very concise title (less than 6 Chinese characters). DO NOT include quotation marks or phrases like "Title: ". Output the title directly.\n\nConversation content:\n${recentMessages}`;

                    let generatedTitle = '';
                    await new Promise<void>((resolve, reject) => {
                        client.streamChat(
                            [{ role: 'user', content: prompt }],
                            (chunk) => {
                                if (chunk.content) generatedTitle += chunk.content;
                            },
                            (err) => { reject(err); }
                        ).then(resolve).catch(reject);
                    });

                    const title = generatedTitle.trim().replace(/^["']|["']$/g, '');

                    if (title) {
                        get().updateSessionTitle(sessionId, title);
                        return title;
                    }
                } catch (error) {
                    console.error('Failed to generate title:', error);
                }
                return undefined;
            }
        }),
        {
            name: 'chat-storage',
            storage: createJSONStorage(() => AsyncStorage),
            partialize: (state) => ({ sessions: state.sessions }), // Don't persist activeRequests
        }
    )
);
