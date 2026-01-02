import { Session, Message, RagReference, RagConfiguration } from '../../types/chat';
import { vectorStore, SearchResult } from './vector-store';
import { EmbeddingClient } from './embedding';
import { useApiStore } from '../../store/api-store';
import { RecursiveCharacterTextSplitter, TrigramTextSplitter } from './text-splitter';
import { db } from '../db';
import { useSettingsStore } from '../../store/settings-store';
import { estimateTokens } from '../../features/chat/utils/token-counter';

export class MemoryManager {
    static async retrieveContext(query: string, sessionId: string, options: {
        enableMemory?: boolean;
        enableDocs?: boolean;
        activeDocIds?: string[];
        activeFolderIds?: string[];
        isGlobal?: boolean;
        ragConfig?: RagConfiguration; // ✅ 新增：允许传入特定 RAG 配置
        onProgress?: (stage: string, percentage: number) => void; // ✅ 新增：进度回调
    } = {}): Promise<{ context: string; references: RagReference[]; metadata?: any; billingUsage?: { ragSystem: number; isEstimated: boolean } }> {

        const { enableMemory = true, enableDocs = true, activeDocIds = [], activeFolderIds = [], isGlobal = false, ragConfig, onProgress } = options;
        const apiStore = useApiStore.getState();
        const settings = useSettingsStore.getState();
        const startTime = Date.now();

        // 🔑 优先级：选项传入 > 全局配置
        const effectiveRagConfig = ragConfig || settings.globalRagConfig;

        // 0. 预先计算文档搜索范围 (Pre-calculate doc auth scope)
        // 这一步提前做，是为了判断是否有必要进行后续的重写和搜索
        let authorizedDocIds: Set<string> | null = null;
        if (enableDocs && !isGlobal) {
            const hasAuthorizedItems = (activeDocIds && activeDocIds.length > 0) || (activeFolderIds && activeFolderIds.length > 0);
            if (hasAuthorizedItems) {
                authorizedDocIds = new Set(activeDocIds);
                if (activeFolderIds && activeFolderIds.length > 0) {
                    try {
                        const allFoldersResult = await db.execute('SELECT id, parent_id FROM folders');
                        const allFolders = (allFoldersResult.rows as any)._array || (allFoldersResult.rows as any) || [];

                        const folderMap = new Map<string, string[]>();
                        allFolders.forEach((f: any) => {
                            const pid = f.parent_id || 'root';
                            if (!folderMap.has(pid)) folderMap.set(pid, []);
                            folderMap.get(pid)?.push(f.id);
                        });

                        const stack = [...activeFolderIds];
                        const expandedFolderIds = new Set(activeFolderIds);
                        while (stack.length > 0) {
                            const fid = stack.pop()!;
                            const children = folderMap.get(fid);
                            if (children) {
                                children.forEach(cid => {
                                    if (!expandedFolderIds.has(cid)) {
                                        expandedFolderIds.add(cid);
                                        stack.push(cid);
                                    }
                                });
                            }
                        }

                        // Get docs in these folders
                        if (expandedFolderIds.size > 0) {
                            const placeholders = Array.from(expandedFolderIds).map(() => '?').join(',');
                            const folderDocsResult = await db.execute(
                                `SELECT id FROM documents WHERE folder_id IN (${placeholders})`,
                                Array.from(expandedFolderIds)
                            );
                            const paramRows = (folderDocsResult.rows as any)._array || (folderDocsResult.rows as any) || [];
                            paramRows.forEach((row: any) => authorizedDocIds?.add(row.id));
                        }
                    } catch (e) {
                        console.error('[MemoryManager] Folder expansion failed:', e);
                    }
                }
            }
        }

        // Quick Exit: If Memory disabled AND Docs enabled but no docs selected (and not global), nothing to search.
        const canSearchDocs = enableDocs && (isGlobal || (authorizedDocIds && authorizedDocIds.size > 0));
        if (!enableMemory && !canSearchDocs) {
            return { context: '', references: [], metadata: { searchTimeMs: 0, rerankTimeMs: 0, recallCount: 0, finalCount: 0, sourceDistribution: { memory: 0, documents: 0 } }, billingUsage: { ragSystem: 0, isEstimated: false } };
        }

        // ===== 阶段 1: Query Rewrite (查询重写) =====
        let queryVariants = [query];
        let rewriteTokenUsage = 0; // Capture rewrite usage
        // Optimization: Rewrite if enabled AND (Global OR Doc Auth OR Memory Enabled)
        // If searching local docs only and no docs selected, skip. But if Memory enabled, proceed.
        const shouldRewrite = effectiveRagConfig.enableQueryRewrite && (isGlobal || enableMemory || (authorizedDocIds && authorizedDocIds.size > 0));

        if (shouldRewrite) {
            onProgress?.('rewriting', 5);
            try {
                // 🔑 模型选择优先级：
                // 1. RAG配置的 queryRewriteModel（如需单独指定）
                // 2. 全局的 defaultSummaryModel（Query Rewrite和Summary共用快速模型）
                const modelId = effectiveRagConfig.queryRewriteModel || settings.defaultSummaryModel;
                const provider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.uuid === modelId || m.id === modelId));

                if (provider && modelId) {
                    const { createLlmClient } = await import('../llm/factory');
                    // Construct ExtendedModelConfig if needed or just pass config
                    // Factory expects ExtendedModelConfig.
                    const modelConfig = provider.models.find(m => m.uuid === modelId || m.id === modelId)!;

                    const llmClient = createLlmClient({
                        ...modelConfig,
                        provider: provider.type,
                        apiKey: provider.apiKey,
                        baseUrl: provider.baseUrl,
                        vertexProject: provider.vertexProject,
                        vertexLocation: provider.vertexLocation,
                        vertexKeyJson: provider.vertexKeyJson
                    });

                    const { QueryRewriter } = await import('./query-rewriter');
                    const rewriter = new QueryRewriter(llmClient, effectiveRagConfig.queryRewriteStrategy as any);

                    // 🔑 添加超时控制：15秒内必须完成，否则降级为原始查询
                    const timeoutPromise = new Promise<{ variants: string[]; usage?: any }>((_, reject) => {
                        setTimeout(() => reject(new Error('Query rewrite timeout')), 15000);
                    });

                    const rewritePromise = rewriter.rewrite(query, effectiveRagConfig.queryRewriteCount || 3);

                    try {
                        const { variants, usage } = await Promise.race([rewritePromise, timeoutPromise]);

                        // Accumulate usage for billing
                        if (usage) {
                            rewriteTokenUsage += usage.total;
                        }

                        if (variants.length > 0) {
                            queryVariants = variants;
                            // Limit total variants to avoid explosion
                            if (queryVariants.length > 5) queryVariants = queryVariants.slice(0, 5);
                        }
                    } catch (timeoutError) {
                        console.warn('[MemoryManager] Query rewrite timeout or error, using original query:', timeoutError);
                        // Fallback: use original query
                        queryVariants = [query];
                    }
                }
            } catch (e) {
                console.warn('[MemoryManager] Query rewrite failed:', e);
            }
        }

        // Loop checks
        const searchPromises = queryVariants.map(async (currentQuery) => {
            // 2. 获取查询向量 (Get Embedding)
            onProgress?.('embedding', 10);

            // 🔑 关键修复: 读取用户配置的 defaultEmbeddingModel
            const settings = useSettingsStore.getState();
            const preferredModelId = settings.defaultEmbeddingModel;

            let provider: any;
            let modelId: string | undefined;

            // 优先级1: 用户显式选择的 Embedding 模型
            if (preferredModelId) {
                provider = apiStore.providers.find(p =>
                    p.enabled && p.models.some((m: any) =>
                        (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled
                    )
                );

                if (provider) {
                    const model = provider.models.find((m: any) =>
                        m.uuid === preferredModelId || m.id === preferredModelId
                    );
                    modelId = model?.id;
                }
            }

            // 优先级2: Fallback 到第一个可用的 Embedding 模型
            if (!provider || !modelId) {
                provider = apiStore.providers.find(p =>
                    p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding')
                );

                if (provider) {
                    const embeddingModel = provider.models.find((m: any) => m.enabled && m.type === 'embedding');
                    modelId = embeddingModel?.id;
                }
            }

            if (!provider || !modelId) return [];

            let queryEmbedding: number[] | null = null;
            try {
                const client = new EmbeddingClient(provider, modelId);
                const result = await client.embedQuery(currentQuery);
                queryEmbedding = result.embedding;

                // Capture usage
                if (result.usage) {
                    rewriteTokenUsage += result.usage.total_tokens; // Accumulate to total RAG usage
                } else {
                    rewriteTokenUsage += estimateTokens(currentQuery);
                }
            } catch (e) {
                console.warn('[MemoryManager] Embedding failed:', e);
                return [];
            }

            const results: SearchResult[] = [];

            // 3.1 记忆搜索
            if (enableMemory) {
                try {
                    const memResults = await vectorStore.search(queryEmbedding, {
                        limit: (effectiveRagConfig.memoryLimit || 5) * 2, // Fetch more for later filtering/dedup
                        threshold: effectiveRagConfig.memoryThreshold,
                        filter: isGlobal ? { type: 'memory' } : { sessionId, type: 'memory' }
                    });
                    results.push(...memResults);
                } catch (e) { console.error(e); }
            }

            // 3.2 文档搜索
            if (enableDocs) {
                const hasAuth = isGlobal || (authorizedDocIds && authorizedDocIds.size > 0);
                if (hasAuth) {
                    try {
                        const docResults = await vectorStore.search(queryEmbedding, {
                            limit: (effectiveRagConfig.docLimit || 5) * 2,
                            threshold: effectiveRagConfig.docThreshold,
                            filter: { type: 'doc' }
                        });

                        // Apply docId filtering if not global
                        const filteredDocs = isGlobal
                            ? docResults
                            : docResults.filter(r => authorizedDocIds!.has(r.docId || ''));

                        results.push(...filteredDocs);
                    } catch (e) { console.error(e); }
                }
            }

            return results;
        });

        const resultsArrays = await Promise.all(searchPromises);
        let allResults = resultsArrays.flat();

        onProgress?.('searching', 40);

        // ===== 阶段 2: Hybrid Search (关键词混合检索) =====
        // RRF Fusion (Reciprocal Rank Fusion)
        if (effectiveRagConfig.enableHybridSearch) {
            try {
                const { KeywordSearch } = await import('./keyword-search');
                const keywordSearch = new KeywordSearch();

                // Document scope filtering for keyword search
                const keywordOptions: any = { sessionId: isGlobal ? undefined : sessionId };
                if (enableDocs && !isGlobal && authorizedDocIds && authorizedDocIds.size > 0) {
                    keywordOptions.docIds = Array.from(authorizedDocIds);
                } else if (enableDocs && !isGlobal && (!authorizedDocIds || authorizedDocIds.size === 0)) {
                    // If docs enabled but no auth ids found (and not global), keyword search on docs should be restricted
                    // This means if activeDocIds/activeFolderIds were provided but resulted in no authorizedDocIds,
                    // then keyword search should also not return document results.
                    // If enableDocs is true but no specific docs/folders are selected, it implies all docs are fair game,
                    // but the `authorizedDocIds` logic above would not have been triggered.
                    // For now, if `enableDocs` is true and `isGlobal` is false, and `authorizedDocIds` is null/empty,
                    // it means no specific docs were selected, so we don't filter by docIds.
                    // The vector search already handles this by not adding docResults if !hasAuth.
                    // For keyword search, if `enableDocs` is true and `isGlobal` is false, and `authorizedDocIds` is null/empty,
                    // it means no specific docs were selected, so we don't filter by docIds.
                    // If `enableDocs` is false, then keyword search should not return doc results.
                    // The `keywordSearch.search` method needs to handle this.
                    // For now, if `enableDocs` is true and `isGlobal` is false, and `authorizedDocIds` is null/empty,
                    // we don't add `docIds` to `keywordOptions`, letting `keywordSearch` search all docs.
                    // This might be a slight divergence from vector search if `hasAuthorizedItems` was false.
                    // Let's refine: if `enableDocs` is true, and `isGlobal` is false, and `hasAuthorizedItems` was false,
                    // then `authorizedDocIds` would be null. In this case, vector search would skip doc search.
                    // Keyword search should also skip doc search.
                    if (!((activeDocIds && activeDocIds.length > 0) || (activeFolderIds && activeFolderIds.length > 0))) {
                        keywordOptions.excludeDocs = true; // Signal to keyword search to exclude docs
                    }
                } else if (!enableDocs) {
                    keywordOptions.excludeDocs = true;
                }

                const keywordResults = await keywordSearch.search(query, 20, keywordOptions); // Fetch more for fusion

                if (keywordResults.length > 0 || allResults.length > 0) {
                    const rrfK = 60;
                    const scoreMap = new Map<string, number>();
                    const nodeMap = new Map<string, SearchResult>();

                    // Helper to accumulate RRF score
                    const addScores = (items: SearchResult[]) => {
                        items.forEach((item, rank) => {
                            const current = scoreMap.get(item.id) || 0;
                            scoreMap.set(item.id, current + (1 / (rrfK + rank + 1)));
                            if (!nodeMap.has(item.id)) nodeMap.set(item.id, item);
                        });
                    };

                    // 1. Vector Results
                    // Dedupe vector results first (within themselves)
                    const uniqueVector = allResults.filter((v, i, a) => a.findIndex(t => t.id === v.id) === i);
                    uniqueVector.sort((a, b) => b.similarity - a.similarity);
                    addScores(uniqueVector);

                    // 2. Keyword Results
                    addScores(keywordResults);

                    // 3. Flatten back to array
                    const fusedResults: SearchResult[] = [];
                    // RRF normalization factor: Max possible score is approx 1/61 + 1/61 = 0.0327
                    // We interpret this as ~98% confidence for UI purposes
                    const rrfMax = (1 / (rrfK + 1)) * 2;

                    for (const [id, score] of scoreMap.entries()) {
                        const node = nodeMap.get(id);
                        if (node) {
                            // Normalize to 0-1 range for UI display
                            let normalized = score / rrfMax;
                            if (normalized > 0.99) normalized = 0.99;
                            if (normalized < 0.01) normalized = 0.01;

                            fusedResults.push({
                                ...node,
                                similarity: normalized
                            });
                        }
                    }

                    // Sort by RRF score
                    fusedResults.sort((a, b) => b.similarity - a.similarity);
                    allResults = fusedResults;
                }
            } catch (e) {
                console.error('[MemoryManager] Hybrid search failed:', e);
            }
        }

        // 4.原本格式化上下文 (Format Context)
        if (allResults.length === 0) return { context: '', references: [], metadata: { searchTimeMs: 0, rerankTimeMs: 0, recallCount: 0, finalCount: 0, sourceDistribution: { memory: 0, documents: 0 } }, billingUsage: { ragSystem: rewriteTokenUsage, isEstimated: false } };

        // Aggregation & Dedup
        allResults.sort((a, b) => b.similarity - a.similarity);
        const uniqueResults = allResults.filter((v, i, a) => a.findIndex(t => t.id === v.id) === i);

        // 优化合并策略
        const topMemories = uniqueResults.filter(r => r.metadata?.type === 'memory').slice(0, effectiveRagConfig.memoryLimit || 3);
        const topDocs = uniqueResults.filter(r => r.metadata?.type === 'doc').slice(0, effectiveRagConfig.docLimit || 5);
        const combined = [...topMemories, ...topDocs];

        const totalLimit = (effectiveRagConfig.memoryLimit || 5) + (effectiveRagConfig.docLimit || 8);
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
            if (!rerankProvider) {
                rerankProvider = apiStore.providers.find(p => p.enabled && p.models.some(m => m.enabled && m.type === 'rerank'));
            }

            if (rerankProvider) {
                let finalRerankModelId = rerankModelId;
                const modelConfig = rerankProvider.models.find(m => m.uuid === rerankModelId || m.id === rerankModelId)
                    || rerankProvider.models.find(m => m.enabled && m.type === 'rerank');

                if (modelConfig) {
                    finalRerankModelId = modelConfig.id;
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

        const endTime = Date.now();
        const rerankDuration = (rerankEndTime && rerankStartTime) ? (rerankEndTime - rerankStartTime) : 0;
        const totalDuration = endTime - startTime;
        const searchTimeMs = totalDuration - rerankDuration;

        const metadata = {
            searchTimeMs: searchTimeMs,
            rerankTimeMs: rerankDuration,
            recallCount: uniqueResults.length,
            finalCount: finalResults.length,
            queryVariants: queryVariants,
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
            metadata,
            billingUsage: {
                ragSystem: rewriteTokenUsage,
                isEstimated: rewriteTokenUsage > 0 && false // If captured from API, it's real. If 0, it doesn't matter. We can refine this.
            }
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
        const settings = useSettingsStore.getState();
        const preferredModelId = settings.defaultEmbeddingModel;

        let provider: any;
        let modelId: string | undefined;

        // 优先级1: 用户显式选择的 Embedding 模型
        if (preferredModelId) {
            provider = apiStore.providers.find(p =>
                p.enabled && p.models.some((m: any) =>
                    (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled
                )
            );

            if (provider) {
                const model = provider.models.find((m: any) =>
                    m.uuid === preferredModelId || m.id === preferredModelId
                );
                modelId = model?.id;
            }
        }

        // 优先级2: Fallback 到第一个可用的 Embedding 模型
        if (!provider || !modelId) {
            provider = apiStore.providers.find(p =>
                p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding')
            );

            if (provider) {
                const embeddingModel = provider.models.find((m: any) => m.enabled && m.type === 'embedding');
                modelId = embeddingModel?.id;
            }
        }

        if (!provider || !modelId) return;

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

            // ✅ 使用 Trigram 分词器（中文友好）
            const splitter = new TrigramTextSplitter({
                chunkSize: ragConfig.memoryChunkSize, // Use memoryChunkSize for turn archiving
                chunkOverlap: ragConfig.chunkOverlap
            });
            const chunks = splitter.splitText(turnText);

            const embeddingClient = new EmbeddingClient(provider, modelId);
            const { embeddings, usage } = await embeddingClient.embedDocuments(chunks);

            // Track usage for archiving
            let archiveTokens = 0;
            let isEstimated = false;

            if (usage) {
                archiveTokens = usage.total_tokens;
            } else {
                archiveTokens = estimateTokens(chunks.join('\n'));
                isEstimated = true;
            }

            // Report to global stats store
            try {
                const { useTokenStatsStore } = await import('../../store/token-stats-store');
                useTokenStatsStore.getState().trackUsage({
                    modelId: modelId || 'embedding-model',
                    usage: {
                        ragSystem: { count: archiveTokens, isEstimated }
                    }
                });
            } catch (e) { console.warn('[MemoryManager] Stats tracking failed', e); }

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
