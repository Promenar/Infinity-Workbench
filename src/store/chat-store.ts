import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Session, SessionId, AgentId, Message, TokenUsage, InferenceParams, GeneratedImageData, RagReference } from '../types/chat';
import { useAgentStore } from './agent-store';
import { useApiStore } from './api-store';
import { createLlmClient } from '../lib/llm/factory';
import { estimateTokens } from '../features/chat/utils/token-counter';
import { performWebSearch } from '../features/chat/utils/web-search';
import { LlmClient } from '../lib/llm/types';
import * as FileSystem from 'expo-file-system/legacy';
import { MemoryManager } from '../lib/rag/memory-manager';

import { ContextManager } from '../features/chat/utils/ContextManager';

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
            getSession: (id) => get().sessions.find((s) => s.id === id),

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

            deleteMessage: (sessionId, messageId) => set((state) => ({
                sessions: state.sessions.map((s) => {
                    if (s.id === sessionId) {
                        return {
                            ...s,
                            messages: s.messages.filter((m) => m.id !== messageId),
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
                    const isRagEnabled = options?.ragOptions?.enableMemory !== false || options?.ragOptions?.enableDocs === true;
                    const apiMessage = { content, images: normalizedImages }; // Use the user's message for retrieval
                    const ragOptions = options?.ragOptions;

                    if (isRagEnabled) {
                        try {
                            // Set loading state
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, [], true);

                            // For Super Assistant, force global search
                            const effectiveRagOptions = {
                                ...ragOptions,
                                isGlobal: sessionId === 'super_assistant' ? true : ragOptions?.isGlobal
                            };

                            const { context: retrievedContext, references } = await MemoryManager.retrieveContext(apiMessage.content, sessionId, effectiveRagOptions);
                            ragContext = retrievedContext;
                            ragReferences = references;

                            // Update with results and turn off loading
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, ragReferences, false);
                        } catch (e) {
                            console.error('RAG Retrieval failed:', e);
                            get().updateMessageContent(sessionId, assistantMsgId, '', undefined, undefined, undefined, [], false);
                        }
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

                    const history = await Promise.all(session.messages.slice(-10).map(async (m: Message) => ({
                        role: m.role,
                        content: await formatContent(m.content, m.images, true)
                    })));

                    contextMsgs = [
                        ...contextMsgs,
                        ...history,
                        { role: 'user', content: await formatContent(content, normalizedImages) }
                    ] as any;

                    const context = ContextManager.trimContext(contextMsgs);
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

                    // Update Session Stats & Title
                    const sessionAllText = (get().getSession(sessionId)?.messages || []).map(m => m.content).join('\n');
                    get().updateSession(sessionId, {
                        stats: { totalTokens: estimateTokens(sessionAllText) }
                    });

                    if (session.messages.length <= 1 || session.title === agent.name || session.title === 'New Conversation') {
                        get().updateSessionTitle(sessionId, content.substring(0, 30) + (content.length > 30 ? '...' : ''));
                    }

                    // 6. RAG 归档 (异步执行 - Fire and Forget)
                    const finalUserContent = content;
                    const finalAiContent = accumulatedContent;
                    setTimeout(async () => {
                        // 归档到向量数据库
                        await MemoryManager.addTurnToMemory(sessionId, finalUserContent, finalAiContent);

                        // 自动摘要处理 (仅当长期记忆开启时)
                        const currentSession = get().getSession(sessionId);
                        if (currentSession && currentSession.ragOptions?.enableMemory !== false) {
                            console.log('[ChatStore] Triggering auto-summary check...');
                            await ContextManager.checkAndSummarize(sessionId, currentSession.messages);
                        }
                    }, 2000); // 小延迟，避免立即影响 UI 交互 (Small delay)

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
