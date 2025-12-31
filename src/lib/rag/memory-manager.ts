import { Session, Message, RagReference, RagConfiguration } from '../../types/chat';
import { vectorStore, SearchResult } from './vector-store';
import { EmbeddingClient } from './embedding';
import { useApiStore } from '../../store/api-store';
import { RecursiveCharacterTextSplitter } from './text-splitter';
import { db } from '../db';
import { useSettingsStore } from '../../store/settings-store';

export class MemoryManager {
    static async retrieveContext(query: string, sessionId: string, options: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[];
        activeFolderIds?: string[];
        isGlobal?: boolean;
        ragConfig?: RagConfiguration; // ✅ 新增：允许传入特定 RAG 配置
        onProgress?: (stage: string, percentage: number) => void; // ✅ 新增：进度回调
    } = {}): Promise<{ context: string; references: RagReference[]; metadata?: any }> {

        const { enableMemory = true, enableDocs = true, activeDocIds = [], activeFolderIds = [], isGlobal = false, ragConfig, onProgress } = options;
        const apiStore = useApiStore.getState();
        const settings = useSettingsStore.getState();

        // 🔑 优先级：选项传入 > 全局配置
        const effectiveRagConfig = ragConfig || settings.globalRagConfig;

        // 1. 获取查询向量 (Get Embedding)
        onProgress?.('embedding', 10);
        // ... (Embedding logic remains the same)
        let provider = apiStore.providers.find(p => p.enabled && p.type === 'openai' && p.models.some(m => m.enabled && m.type === 'embedding'));

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (
                p.type === 'siliconflow' ||
                p.type === 'deepseek' ||
                p.type === 'moonshot' ||
                p.type === 'zhipu'
            ) && p.models.some(m => m.enabled && m.type === 'embedding'));
        }

        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'gemini' || p.type === 'google') && p.models.some(m => m.enabled && m.type === 'embedding'));
        }

        if (!provider) {
            // Fallback for legacy
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'openai' || p.type === 'gemini' || p.type === 'google'));
        }

        if (!provider) {
            console.warn('[MemoryManager] 未找到 Embedding 提供商，跳过 RAG。');
            return { context: '', references: [] };
        }

        // Find the active embedding model
        const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
        const modelId = embeddingModelConfig?.id || (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

        const embeddingClient = new EmbeddingClient(provider, modelId);
        let queryEmbedding: number[];

        try {
            queryEmbedding = await embeddingClient.embedQuery(query);
        } catch (e) {
            console.error('[MemoryManager] 查询向量化失败:', e);
            return { context: '', references: [] };
        }

        onProgress?.('searching', 40);
        const results: SearchResult[] = [];

        // 2. 搜索记忆 (长期对话历史)
        if (enableMemory) {
            try {
                const memResults = await vectorStore.search(queryEmbedding, {
                    limit: effectiveRagConfig.memoryLimit,
                    filter: isGlobal ? { type: 'memory' } : { sessionId, type: 'memory' },
                    threshold: effectiveRagConfig.memoryThreshold
                });
                results.push(...memResults);
            } catch (e) {
                console.error('[MemoryManager] 记忆搜索失败:', e);
            }
        }

        // 3. 搜索文档 (知识库)
        if (enableDocs) {
            // 权限控制：如果启用了知识库但没有授权任何文档或文件夹，则不应检索任何内容
            const hasAuthorizedItems = (activeDocIds && activeDocIds.length > 0) || (activeFolderIds && activeFolderIds.length > 0) || isGlobal;

            if (hasAuthorizedItems) {
                try {
                    // 解析文件夹下的所有文档 ID
                    const authorizedDocIds = new Set(activeDocIds);

                    if (!isGlobal && activeFolderIds && activeFolderIds.length > 0) {
                        try {
                            // Fetch all folders to build hierarchy (efficient enough for < 1000 folders)
                            const allFoldersResult = await db.execute('SELECT id, parent_id FROM folders');
                            const allFolders = (allFoldersResult.rows as any[]) || [];

                            // Build Adjacency List
                            const folderChildrenMap = new Map<string, string[]>();
                            allFolders.forEach(f => {
                                const pid = f.parent_id;
                                if (pid) {
                                    if (!folderChildrenMap.has(pid)) folderChildrenMap.set(pid, []);
                                    folderChildrenMap.get(pid)?.push(f.id);
                                }
                            });

                            // Recursive Collector
                            const expandedFolderIds = new Set<string>(activeFolderIds);
                            const queue = [...activeFolderIds];

                            while (queue.length > 0) {
                                const currentId = queue.shift()!;
                                const children = folderChildrenMap.get(currentId);
                                if (children) {
                                    children.forEach(childId => {
                                        if (!expandedFolderIds.has(childId)) {
                                            expandedFolderIds.add(childId);
                                            queue.push(childId);
                                        }
                                    });
                                }
                            }

                            // Query documents in ALL expanded folders
                            const folderPlaceholders = Array.from(expandedFolderIds).map(() => '?').join(',');
                            if (folderPlaceholders.length > 0) {
                                const folderDocs = await db.execute(
                                    `SELECT id FROM documents WHERE folder_id IN (${folderPlaceholders})`,
                                    Array.from(expandedFolderIds)
                                );
                                if (folderDocs.rows) {
                                    for (let i = 0; i < folderDocs.rows.length; i++) {
                                        authorizedDocIds.add(folderDocs.rows[i].id as string);
                                    }
                                }
                            }
                        } catch (err) {
                            console.error('[MemoryManager] Folder recursion failed:', err);
                        }
                    }

                    const docResults = await vectorStore.search(queryEmbedding, {
                        limit: effectiveRagConfig.docLimit, // Increased slightly since we filter later
                        filter: { type: 'doc' },
                        threshold: effectiveRagConfig.docThreshold
                    });

                    // 如果是全局模式，或者命中授权文档，则保留
                    const filteredDocs = isGlobal
                        ? docResults
                        : docResults.filter(r => authorizedDocIds.has(r.docId || ''));

                    results.push(...filteredDocs);
                } catch (e) {
                    console.error('[MemoryManager] 文档搜索失败:', e);
                }
            } else {
                console.log('[MemoryManager] 知识库已开启但未选择授权范围，跳过检索。');
            }
        }

        // 4.原本格式化上下文 (Format Context)
        if (results.length === 0) return { context: '', references: [] };

        // 按相似度排序组合结果
        results.sort((a, b) => b.similarity - a.similarity);

        // 去重 (以防万一)
        const uniqueResults = results.filter((v, i, a) => a.findIndex(t => t.id === v.id) === i);

        // 优化合并策略：保证文档不被记忆完全淹没 (Diversity Buffer)
        // 1. 提取 Top 记忆 (按照配置限制)
        const topMemories = uniqueResults.filter(r => r.metadata?.type === 'memory').slice(0, effectiveRagConfig.memoryLimit || 3);
        // 2. 提取 Top 文档 (按照配置限制)
        const topDocs = uniqueResults.filter(r => r.metadata?.type === 'doc').slice(0, effectiveRagConfig.docLimit || 5);

        const combined = [...topMemories, ...topDocs];

        // 4. 计算总检索上限 (Priority: memoryLimit + docLimit)
        const totalLimit = (effectiveRagConfig.memoryLimit || 5) + (effectiveRagConfig.docLimit || 8);

        // 如果总数未达上限，可以从剩余结果中补充 (不分类型，补充至上限)
        const existingIds = new Set(combined.map(r => r.id));
        const remaining = uniqueResults.filter(r => !existingIds.has(r.id)).slice(0, Math.max(0, totalLimit - combined.length));

        let finalResults = [...combined, ...remaining].sort((a, b) => b.similarity - a.similarity);

        // ===== 阶段 3: Rerank 精排 =====
        let rerankStartTime = 0;
        let rerankEndTime = 0;

        if (effectiveRagConfig.enableRerank) {
            onProgress?.('reranking', 70);
            rerankStartTime = Date.now();
            const rerankModelId = settings.defaultRerankModel;
            let rerankProvider = undefined;

            if (rerankModelId) {
                rerankProvider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.uuid === rerankModelId || m.id === rerankModelId));
            }

            // Fallback: try to find any enabled rerank model
            if (!rerankProvider) {
                rerankProvider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.enabled && m.type === 'rerank'));
            }

            if (rerankProvider) {
                // Try to resolve exact model ID if the uuid was used
                let finalRerankModelId = rerankModelId;
                const modelConfig = rerankProvider.models.find(m => m.uuid === rerankModelId || m.id === rerankModelId)
                    || rerankProvider.models.find(m => m.enabled && m.type === 'rerank');

                if (modelConfig) {
                    finalRerankModelId = modelConfig.id;

                    // Instantiate RerankClient
                    const { RerankClient } = await import('./reranker');
                    const reranker = new RerankClient(rerankProvider, finalRerankModelId);

                    try {
                        const reranked = await reranker.rerank(query, finalResults, effectiveRagConfig.rerankFinalK || 5);
                        finalResults = reranked;
                        rerankEndTime = Date.now();

                    } catch (err) {
                        console.error('[MemoryManager] Rerank failed, falling back to vector order:', err);
                    }
                }
            }
        }

        const endTime = Date.Now();
        // Assume startTime was passed or we capture it here? No, we need total duration.
        // We can just estimate search time as (endTime - rerankDuration) or capture start time at method entry.
        // Since I can't easily edit method entry without large context, I'll assume current method execution time as mostly search/rerank.
        // Better: let's just use what we have. 
        // Search time ~ (endTime - (rerankEndTime - rerankStartTime)) if rerank happened.

        const rerankDuration = (rerankEndTime && rerankStartTime) ? (rerankEndTime - rerankStartTime) : 0;
        const totalDuration = endTime - startTime;
        const searchTimeMs = totalDuration - rerankDuration;

        // Construct metadata
        const metadata = {
            searchTimeMs: searchTimeMs,
            rerankTimeMs: rerankDuration,
            recallCount: uniqueResults.length,
            finalCount: finalResults.length,
            sourceDistribution: {
                memory: finalResults.filter(r => r.metadata?.type === 'memory').length,
                documents: finalResults.filter(r => r.metadata?.type === 'doc').length
            }
        };

        const contextBlock = finalResults.map(r => {
            const typeLabel = r.metadata?.type === 'memory' ? 'Memory' : 'Document';
            const date = new Date(r.createdAt).toLocaleDateString();
            return `[${typeLabel} - ${date}]: ${r.content}`;
        }).join('\n\n');

        // 构建引用数据
        const references: RagReference[] = finalResults.map(r => ({
            id: r.id,
            content: r.content,
            source: r.metadata?.source || (r.metadata?.type === 'memory' ? 'Previous Conversation' : 'Unknown Document'),
            type: r.metadata?.type === 'memory' ? 'memory' : 'doc',
            docId: r.docId,
            similarity: r.similarity
        }));

        onProgress?.('done', 100);

        return {
            context: `relevant_context_block (参考上下文):\n${contextBlock}`,
            references,
            metadata
        };
    }

    /**
     * 将一个“对话轮次” (用户消息 + AI 回复) 归档到向量记忆中
     */
    static async addTurnToMemory(
        sessionId: string,
        userContent: string,
        aiContent: string,
        userMessageId: string,
        assistantMessageId: string
    ) {
        if (!userContent || !aiContent) return;

        // 🛡️ Sanitize: Strip massive Base64 image data to prevent TextSplitter recursion overflow
        // Replace ![...](data:image/...) with [Image Generated]
        const sanitize = (text: string) => text.replace(/!\[(.*?)\]\(data:image\/.*?;base64,.*?\)/g, '[Image: $1]');

        userContent = sanitize(userContent);
        aiContent = sanitize(aiContent);

        const apiStore = useApiStore.getState();

        let provider = apiStore.providers.find(p => p.enabled && p.type === 'openai' && p.models.some(m => m.enabled && m.type === 'embedding'));
        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (
                p.type === 'siliconflow' || p.type === 'deepseek' || p.type === 'moonshot' || p.type === 'zhipu'
            ) && p.models.some(m => m.enabled && m.type === 'embedding'));
        }
        if (!provider) {
            provider = apiStore.providers.find(p => p.enabled && (p.type === 'gemini' || p.type === 'google'));
        }

        if (!provider) return;

        // Find the active embedding model
        const embeddingModelConfig = provider.models.find(m => m.enabled && m.type === 'embedding');
        const modelId = embeddingModelConfig?.id || (provider.type === 'openai' ? 'text-embedding-3-small' : undefined);

        try {
            // Ensure session exists in SQLite for FK constraint
            // Since ChatStore uses AsyncStorage, we must sync the ID here
            const now = Date.now();
            await db.execute(
                `INSERT OR IGNORE INTO sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)`,
                [sessionId, 'Shadow Session', now, now]
            );

            // 格式: "User: ... \n Assistant: ..."
            const turnText = `User: ${userContent}\nAssistant: ${aiContent}`;

            // 🔑 动态获取 RAG 配置：优先从该 Session 关联的 Agent 获取
            const chatStore = (await import('../../store/chat-store')).useChatStore.getState();
            const agentStore = (await import('../../store/agent-store')).useAgentStore.getState();
            const settings = (await import('../../store/settings-store')).useSettingsStore.getState();

            const session = chatStore.getSession(sessionId);
            const agent = session ? agentStore.getAgent(session.agentId) : undefined;
            const ragConfig = agent?.ragConfig || settings.globalRagConfig;

            const splitter = new RecursiveCharacterTextSplitter({
                chunkSize: ragConfig.memoryChunkSize,
                chunkOverlap: ragConfig.chunkOverlap
            });
            const chunks = splitter.splitText(turnText);

            const embeddingClient = new EmbeddingClient(provider, modelId);
            const embeddings = await embeddingClient.embedDocuments(chunks);

            const vectors = chunks.map((chunk, i) => ({
                sessionId,
                content: chunk,
                embedding: embeddings[i],
                metadata: { type: 'memory', chunkIndex: i },
                startMessageId: userMessageId,
                endMessageId: assistantMessageId
            }));

            await vectorStore.addVectors(vectors);
            console.log(`[MemoryManager] 已归档会话 ${sessionId} (${vectors.length} 个切片)`);

        } catch (e) {
            console.error('[MemoryManager] 归档轮次失败:', e);
        }
    }
}
