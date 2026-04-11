import { Session, Message, RagReference, RagConfiguration } from '../../types/chat';
import { vectorStore, SearchResult } from './vector-store';
import { EmbeddingClient } from './embedding';
import { useApiStore } from '../../store/api-store';
import { RecursiveCharacterTextSplitter, TrigramTextSplitter } from './text-splitter';
import { db } from '../db';
import { useSettingsStore } from '../../store/settings-store';
import { estimateTokens } from '../../features/chat/utils/token-counter';

export class MemoryManager {
  static async retrieveContext(
    query: string,
    sessionId: string,
    options: {
      enableMemory?: boolean;
      enableDocs?: boolean;
      activeDocIds?: string[];
      activeFolderIds?: string[];
      isGlobal?: boolean;
      ragConfig?: RagConfiguration;
      onProgress?: (stage: string, percentage: number, subStage?: string, networkStats?: any) => void; // ✅ 升级回调支持子阶段和网络统计
    } = {},
  ): Promise<{
    context: string;
    references: RagReference[];
    metadata?: any;
    billingUsage?: { ragSystem: number; isEstimated: boolean };
  }> {
    const {
      enableMemory = true,
      enableDocs = true,
      activeDocIds = [],
      activeFolderIds = [],
      isGlobal = false,
      ragConfig,
      onProgress,
    } = options;
    const apiStore = useApiStore.getState();
    const settings = useSettingsStore.getState();
    const startTime = Date.now();

    // 🔑 优先级：选项传入 > 全局配置 (并进行合并以确保基础字段存在)
    const effectiveRagConfig = { ...settings.globalRagConfig, ...(ragConfig || {}) };

    // 0. 预先计算文档搜索范围 (Pre-calculate doc auth scope)
    // 这一步提前做，是为了判断是否有必要进行后续的重写和搜索
    let authorizedDocIds: Set<string> | null = null;
    if (enableDocs && !isGlobal) {
      const hasAuthorizedItems =
        (activeDocIds && activeDocIds.length > 0) ||
        (activeFolderIds && activeFolderIds.length > 0);
      if (hasAuthorizedItems) {
        authorizedDocIds = new Set(activeDocIds);
        if (activeFolderIds && activeFolderIds.length > 0) {
          try {
            const allFoldersResult = await db.execute('SELECT id, parent_id FROM folders');
            const allFolders =
              (allFoldersResult.rows as any)._array || (allFoldersResult.rows as any) || [];

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
                children.forEach((cid) => {
                  if (!expandedFolderIds.has(cid)) {
                    expandedFolderIds.add(cid);
                    stack.push(cid);
                  }
                });
              }
            }

            // Get docs in these folders
            if (expandedFolderIds.size > 0) {
              const placeholders = Array.from(expandedFolderIds)
                .map(() => '?')
                .join(',');
              const folderDocsResult = await db.execute(
                `SELECT id FROM documents WHERE folder_id IN (${placeholders})`,
                Array.from(expandedFolderIds),
              );
              const paramRows =
                (folderDocsResult.rows as any)._array || (folderDocsResult.rows as any) || [];
              paramRows.forEach((row: any) => authorizedDocIds?.add(row.id));
            }
          } catch (e) {
            console.error('[MemoryManager] Folder expansion failed:', e);
          }
        }
      }
    }

    // Quick Exit: If Memory disabled AND Docs enabled but no docs selected (and not global), nothing to search.
    const canSearchDocs =
      enableDocs && (isGlobal || (authorizedDocIds && authorizedDocIds.size > 0));
    if (!enableMemory && !canSearchDocs) {
      return {
        context: '',
        references: [],
        metadata: {
          searchTimeMs: 0,
          rerankTimeMs: 0,
          recallCount: 0,
          finalCount: 0,
          sourceDistribution: { memory: 0, documents: 0 },
        },
        billingUsage: { ragSystem: 0, isEstimated: false },
      };
    }

    // ===== 阶段 1: Query Rewrite (查询重写) =====
    let queryVariants = [query];
    let rewriteTokenUsage = 0; // Capture rewrite usage
    // Optimization: Rewrite if enabled AND (Global OR Doc Auth OR Memory Enabled)
    // If searching local docs only and no docs selected, skip. But if Memory enabled, proceed.
    const shouldRewrite =
      effectiveRagConfig.enableQueryRewrite &&
      (isGlobal || enableMemory || (authorizedDocIds && authorizedDocIds.size > 0));

    if (shouldRewrite) {
      onProgress?.('rewriting', 5, 'INTENT');
      console.log('[MemoryManager] STAGE: Query Rewrite START');
      try {
        const modelId = effectiveRagConfig.queryRewriteModel || settings.defaultSummaryModel;
        const provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m) => m.uuid === modelId || m.id === modelId),
        );

        if (provider && modelId) {
          const { createLlmClient } = await import('../llm/factory');
          const modelConfig = provider.models.find((m) => m.uuid === modelId || m.id === modelId)!;

          const llmClient = createLlmClient({
            ...modelConfig,
            provider: provider.type,
            apiKey: provider.apiKey,
            baseUrl: provider.baseUrl,
            vertexProject: provider.vertexProject,
            vertexLocation: provider.vertexLocation,
            vertexKeyJson: provider.vertexKeyJson,
          });

          const { QueryRewriter } = await import('./query-rewriter');
          const rewriter = new QueryRewriter(
            llmClient,
            effectiveRagConfig.queryRewriteStrategy as any,
          );

          // 15s timeout
          const timeoutPromise = new Promise<{ variants: string[]; usage?: any }>((_, reject) => {
            setTimeout(() => reject(new Error('Query rewrite timeout')), 15000);
          });

          onProgress?.('rewriting', 10, 'API_TX');
          const rewritePromise = rewriter.rewrite(query, effectiveRagConfig.queryRewriteCount || 3);

          try {
            const { variants, usage } = await Promise.race([rewritePromise, timeoutPromise]);
            onProgress?.('rewriting', 15, 'API_RX');

            if (usage) {
              rewriteTokenUsage += usage.total;
            }

            if (variants.length > 0) {
              queryVariants = variants;
              if (queryVariants.length > 5) queryVariants = queryVariants.slice(0, 5);
            }
            console.log('[MemoryManager] STAGE: Query Rewrite END');
          } catch (timeoutError) {
            console.error('[MemoryManager] STAGE: Query Rewrite TIMEOUT/ERROR:', timeoutError);
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
      onProgress?.('embedding', 20, 'EMBEDDING');

      // 🔑 关键修复: 读取用户配置的 defaultEmbeddingModel
      const settings = useSettingsStore.getState();
      const preferredModelId = settings.defaultEmbeddingModel;

      let provider: any;
      let modelId: string | undefined;

      // 优先级1: 用户显式选择的 Embedding 模型
      if (preferredModelId) {
        provider = apiStore.providers.find(
          (p) =>
            p.enabled &&
            p.models.some(
              (m: any) => (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled,
            ),
        );

        if (provider) {
          const model = provider.models.find(
            (m: any) => m.uuid === preferredModelId || m.id === preferredModelId,
          );
          modelId = model?.id;
        }
      }

      // 优先级2: Fallback 到第一个可用的 Embedding 模型
      if (!provider || !modelId) {
        provider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding'),
        );

        if (provider) {
          const embeddingModel = provider.models.find(
            (m: any) => m.enabled && m.type === 'embedding',
          );
          modelId = embeddingModel?.id;
        }
      }

      if (!provider || !modelId) return [];

      console.log('[MemoryManager] STAGE: Embedding START');
      let queryEmbedding: number[] | null = null;
      try {
        const client = new EmbeddingClient(provider, modelId);

        // 15s timeout for embedding
        const embedTimeoutPromise = new Promise<any>((_, reject) => {
          setTimeout(() => reject(new Error('Embedding stage timeout')), 15000);
        });

        const result = await Promise.race([client.embedQuery(currentQuery), embedTimeoutPromise]);
        queryEmbedding = result.embedding;

        if (result.usage) {
          rewriteTokenUsage += result.usage.total_tokens;
        } else {
          rewriteTokenUsage += estimateTokens(currentQuery);
        }
        console.log('[MemoryManager] STAGE: Embedding END');
      } catch (e) {
        console.error('[MemoryManager] STAGE: Embedding TIMEOUT/ERROR:', e);
        return [];
      }

      if (!queryEmbedding) return [];

      const results: SearchResult[] = [];

      // 🔑 优化：Memory 和 Doc 搜索并行执行
      onProgress?.('searching', 45, 'LOCAL_SCAN');

      const searchTasks: Promise<SearchResult[]>[] = [];

      // 3.1 记忆搜索任务 (Raw Memory)
      if (enableMemory) {
        const memoryTask = (async () => {
          try {
            const initialRecallLimit = effectiveRagConfig.enableRerank || effectiveRagConfig.enableHybridSearch
              ? (effectiveRagConfig.rerankTopK || 30)
              : (effectiveRagConfig.memoryLimit || 5) * 2;

            return await vectorStore.search(queryEmbedding, {
              limit: initialRecallLimit,
              threshold: effectiveRagConfig.memoryThreshold,
              filter: isGlobal ? { type: 'memory' } : { sessionId, type: 'memory' },
            });
          } catch (e) {
            console.error('[MemoryManager] Memory search failed:', e);
            return [];
          }
        })();
        searchTasks.push(memoryTask);

        // 3.1.5 摘要搜索任务 (Summary)
        // 🔑 新增：显式搜索 type='summary' 的向量，用于召回深度历史
        const summaryTask = (async () => {
          try {
            // 摘要数量通常较少，且包含高密度信息，召回限制可适度放宽
            // 默认召回 5 条摘要 (或根据 Rerank 配置翻倍)
            const summaryLimit = effectiveRagConfig.enableRerank ? 10 : 5;

            return await vectorStore.search(queryEmbedding, {
              limit: summaryLimit,
              // 摘要通常语义更宏观，阈值稍微降低以增加召回率
              threshold: (effectiveRagConfig.memoryThreshold || 0.6) - 0.05,
              filter: isGlobal ? { type: 'summary' } : { sessionId, type: 'summary' },
            });
          } catch (e) {
            console.error('[MemoryManager] Summary search failed:', e);
            return [];
          }
        })();
        searchTasks.push(summaryTask);
      }

      // 3.2 文档搜索任务
      if (enableDocs) {
        const hasAuth = isGlobal || (authorizedDocIds && authorizedDocIds.size > 0);
        if (hasAuth) {
          const docTask = (async () => {
            try {
              const initialRecallLimit = effectiveRagConfig.enableRerank || effectiveRagConfig.enableHybridSearch
                ? (effectiveRagConfig.rerankTopK || 30)
                : (effectiveRagConfig.docLimit || 8) * 2;

              return await vectorStore.search(queryEmbedding, {
                limit: initialRecallLimit,
                threshold: effectiveRagConfig.docThreshold,
                filter: isGlobal
                  ? { type: 'doc' }
                  : { type: 'doc', docIds: Array.from(authorizedDocIds!) },
              });
            } catch (e) {
              console.error('[MemoryManager] Doc search failed:', e);
              return [];
            }
          })();
          searchTasks.push(docTask);
        }
      }

      // 🔑 并行执行所有搜索任务 (15s timeout)
      console.log('[MemoryManager] STAGE: Parallel Vector Search START');
      const vectorSearchTimeoutPromise = new Promise<any>((_, reject) => {
        setTimeout(() => reject(new Error('Vector search stage timeout')), 15000);
      });

      try {
        const searchResults = await Promise.race([Promise.all(searchTasks), vectorSearchTimeoutPromise]);
        searchResults.forEach((r: any) => results.push(...r));
        console.log('[MemoryManager] STAGE: Parallel Vector Search END');
      } catch (e) {
        console.error('[MemoryManager] STAGE: Parallel Vector Search TIMEOUT/ERROR:', e);
        // Continue with what we have
      }

      return results;
    });

    const resultsArrays = await Promise.all(searchPromises);
    let allResults = resultsArrays.flat();

    onProgress?.('searching', 55, 'REF_FOUND');

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
          if (
            !(
              (activeDocIds && activeDocIds.length > 0) ||
              (activeFolderIds && activeFolderIds.length > 0)
            )
          ) {
            keywordOptions.excludeDocs = true; // Signal to keyword search to exclude docs
          }
        } else if (!enableDocs) {
          keywordOptions.excludeDocs = true;
        }

        console.log('[MemoryManager] STAGE: Hybrid Search START');
        const hybridSearchTimeoutPromise = new Promise<any>((_, reject) => {
          setTimeout(() => reject(new Error('Hybrid search stage timeout')), 10000);
        });

        const keywordResults = await Promise.race([
          keywordSearch.search(
            query,
            effectiveRagConfig.rerankTopK || 30, // 使用配置的初召回深度
            keywordOptions
          ),
          hybridSearchTimeoutPromise
        ]);

        if (keywordResults.length > 0 || allResults.length > 0) {
          const rrfK = 60;
          const scoreMap = new Map<string, number>();
          const nodeMap = new Map<string, SearchResult>();

          // 🔑 配置读取：权重与增益
          const alpha = effectiveRagConfig.hybridAlpha ?? 0.6;
          const bm25Boost = effectiveRagConfig.hybridBM25Boost ?? 1.0;

          // Helper to accumulate Weighted RRF score
          const addScores = (items: SearchResult[], weight: number) => {
            items.forEach((item, rank) => {
              const current = scoreMap.get(item.id) || 0;
              // 算法：FusedScore = current + weight * (1 / (rrfK + rank + 1))
              scoreMap.set(item.id, current + weight * (1 / (rrfK + rank + 1)));
              if (!nodeMap.has(item.id)) nodeMap.set(item.id, item);
            });
          };

          // 1. Vector Results (加权 Alpha)
          // Dedupe vector results first (within themselves)
          const uniqueVector = allResults.filter(
            (v, i, a) => a.findIndex((t) => t.id === v.id) === i,
          );
          uniqueVector.sort((a, b) => b.similarity - a.similarity);
          addScores(uniqueVector, alpha);

          // 2. Keyword Results (加权 1-Alpha 乘以增强系数)
          addScores(keywordResults, (1 - alpha) * bm25Boost);

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
                similarity: normalized,
              });
            }
          }

          // Sort by RRF score
          fusedResults.sort((a, b) => b.similarity - a.similarity);
          allResults = fusedResults;
        }
        console.log('[MemoryManager] STAGE: Hybrid Search END');
      } catch (e) {
        console.error('[MemoryManager] STAGE: Hybrid Search TIMEOUT/ERROR:', e);
      }
    }

    // 4.原本格式化上下文 (Format Context)
    if (allResults.length === 0)
      return {
        context: '',
        references: [],
        metadata: {
          searchTimeMs: 0,
          rerankTimeMs: 0,
          recallCount: 0,
          finalCount: 0,
          sourceDistribution: { memory: 0, documents: 0 },
        },
        billingUsage: { ragSystem: rewriteTokenUsage, isEstimated: false },
      };

    // Aggregation & Dedup
    allResults.sort((a, b) => b.similarity - a.similarity);
    const uniqueResults = allResults.filter((v, i, a) => a.findIndex((t) => t.id === v.id) === i);

    // 🔑 Phase 2: JIT Micro-Graph Fire (并行触发)
    // 在 Rerank 之前或同时启动 JIT，以最大化重叠耗时
    let jitPromise: Promise<any> | null = null;
    const isJitEnabled = !!effectiveRagConfig.jitMaxChunks && effectiveRagConfig.jitMaxChunks > 0;
    
    if (isJitEnabled && uniqueResults.length > 0) {
      // Lazy JIT check: 只有在现有图谱覆盖不足时才触发
      const densityCheck = async () => {
        const topChunks = uniqueResults.slice(0, 3);
        const hasEdges = await MemoryManager.checkKgDensity(topChunks);
        if (!hasEdges) {
          const { microGraphExtractor } = await import('./micro-graph-extractor');
          return microGraphExtractor.extract(
            topChunks.slice(0, effectiveRagConfig.jitMaxChunks || 3),
            query,
            sessionId,
            { 
              timeout: effectiveRagConfig.jitTimeoutMs || 5000,
              maxChars: effectiveRagConfig.jitMaxCharsPerChunk ? (effectiveRagConfig.jitMaxCharsPerChunk * 3) : 6000,
              onProgress: (sub) => onProgress?.('kg_searching', 90, sub)
            }
          );
        }
        return null;
      };
      jitPromise = densityCheck();
    }

    let finalResults: SearchResult[];

    if (effectiveRagConfig.enableRerank) {
      // 🔑 Rerank 优化：跳过由 memoryLimit/docLimit 引起的中途截断
      // 保留所有去重后的结果（上限设为初筛 2 倍以防结果过载），将其提交给重排模型
      const recallLimit = (effectiveRagConfig.rerankTopK || 30) * 2;
      finalResults = uniqueResults.slice(0, recallLimit);
    } else {
      // 优化合并策略
      const topMemories = uniqueResults
        .filter((r) => r.metadata?.type === 'memory')
        .slice(0, effectiveRagConfig.memoryLimit || 3);
      const topDocs = uniqueResults
        .filter((r) => r.metadata?.type === 'doc')
        .slice(0, effectiveRagConfig.docLimit || 5);
      const combined = [...topMemories, ...topDocs];

      const totalLimit = (effectiveRagConfig.memoryLimit || 5) + (effectiveRagConfig.docLimit || 8);
      const existingIds = new Set(combined.map((r) => r.id));
      const remaining = uniqueResults
        .filter((r) => !existingIds.has(r.id))
        .slice(0, Math.max(0, totalLimit - combined.length));

      finalResults = [...combined, ...remaining].sort((a, b) => b.similarity - a.similarity);
    }

    // 🔑 在 Rerank 前备份原始相似度
    finalResults = finalResults.map(r => ({
      ...r,
      originalSimilarity: r.similarity
    }));

    // ===== 阶段 3: Rerank 精排 =====
    let rerankStartTime = 0;
    let rerankEndTime = 0;

    if (effectiveRagConfig.enableRerank) {
      onProgress?.('reranking', 70, 'RERANK');
      rerankStartTime = Date.now();
      const rerankModelId = settings.defaultRerankModel;
      let rerankProvider = undefined;

      if (rerankModelId) {
        rerankProvider = apiStore.providers.find(
          (p) =>
            p.enabled && p.models.some((m) => m.uuid === rerankModelId || m.id === rerankModelId),
        );
      }
      if (!rerankProvider) {
        rerankProvider = apiStore.providers.find(
          (p) => p.enabled && p.models.some((m) => m.enabled && m.type === 'rerank'),
        );
      }

      if (rerankProvider) {
        let finalRerankModelId = rerankModelId;
        const modelConfig =
          rerankProvider.models.find((m) => m.uuid === rerankModelId || m.id === rerankModelId) ||
          rerankProvider.models.find((m) => m.enabled && m.type === 'rerank');

        if (modelConfig) {
          finalRerankModelId = modelConfig.id;
          const { RerankClient } = await import('./reranker');
          const reranker = new RerankClient(rerankProvider, finalRerankModelId);

          try {
            onProgress?.('reranking', 75, 'API_TX');
            const reranked = await reranker.rerank(
              query,
              finalResults,
              effectiveRagConfig.rerankFinalK || 5,
              (stats) => {
                const subStage = stats.rxBytes ? 'API_RX' : (stats.txBytes ? 'API_TX' : 'API_WAIT');
                onProgress?.('reranking', 80, subStage, stats);
              }
            );
            onProgress?.('reranking', 90, 'RE-SCORING');
            finalResults = reranked;
            rerankEndTime = Date.now();
          } catch (err) {
            console.error('[MemoryManager] Rerank failed, falling back to vector order:', err);
          }
        }
      }
    }

    const endTime = Date.now();
    const rerankDuration = rerankEndTime && rerankStartTime ? rerankEndTime - rerankStartTime : 0;
    const totalDuration = endTime - startTime;
    const searchTimeMs = totalDuration - rerankDuration;

    // 获取指标：最大相似度
    const maxSimilarity = finalResults.length > 0 ? Math.max(...finalResults.map(r => r.similarity)) : 0;

    const metadata = {
      searchTimeMs: searchTimeMs,
      rerankTimeMs: rerankDuration,
      totalTimeMs: totalDuration,
      recallCount: uniqueResults.length,
      finalCount: finalResults.length,
      maxSimilarity: parseFloat(maxSimilarity.toFixed(4)),
      queryVariants: queryVariants,
      sourceDistribution: {
        memory: finalResults.filter((r) => r.metadata?.type === 'memory').length,
        documents: finalResults.filter((r) => r.metadata?.type === 'doc').length,
      },
    };

    // 如果开启了指标追踪，记录到控制台以便后续对接埋点服务
    if (effectiveRagConfig.trackRetrievalMetrics) {
      console.log('[MemoryManager] Retrieval Metrics:', JSON.stringify(metadata));
    }

    const contextBlock = finalResults
      .map((r) => {
        const typeLabel = r.metadata?.type === 'memory' ? 'Memory' : 'Document';
        const date = new Date(r.createdAt).toLocaleDateString();
        return `[${typeLabel} - ${date}]: ${r.content}`;
      })
      .join('\n\n');

    const references: RagReference[] = finalResults.map((r) => ({
      id: r.id,
      content: r.content,
      source:
        r.metadata?.source ||
        (r.metadata?.type === 'memory' ? 'Previous Conversation' : 'Unknown Document'),
      type: r.metadata?.type === 'memory' ? 'memory' : 'doc',
      docId: r.docId,
      similarity: r.similarity,
      originalSimilarity: r.originalSimilarity, // 🚨 新增：传递原始分数给 UI
    }));

    // ===== 阶段 4: Knowledge Graph Retrieval (知识图谱检索) =====
    let kgContext = '';
    const enableKG = effectiveRagConfig.enableKnowledgeGraph;

    if (enableKG && finalResults.length > 0) {
      onProgress?.('kg_searching', 85, 'KG_SCAN');
      const kgStartTime = Date.now();
      try {
        // 🔑 策略：基于召回的文本块进行实体挖掘与一跳扩展
        // 这是一个“读时增强”策略，通过已召回的向量结果反查 KG
        const allReferenceText = finalResults.map(r => r.content).join('\n');

        // 1. 获取召回文档关联的所有实体节点 (避免全表扫描)
        // 策略：只查找与当前召回 docIds 或全局常识 (docId IS NULL) 相关的节点
        const authDocList = Array.from(authorizedDocIds || []);
        let docFilter = 'e.doc_id IS NULL';
        if (enableDocs && !isGlobal && authDocList.length > 0) {
          const placeholders = authDocList.map(() => '?').join(',');
          docFilter += ` OR e.doc_id IN (${placeholders})`;
        } else if (enableDocs && isGlobal) {
          docFilter = '1=1';
        }

        const nodesRes = await db.execute(
          `SELECT DISTINCT n.id, n.name, n.type 
           FROM kg_nodes n
           JOIN kg_edges e ON (n.id = e.source_id OR n.id = e.target_id)
           WHERE ${docFilter}`,
          enableDocs && !isGlobal && authDocList.length > 0 ? authDocList : []
        );
        const allNodes = (nodesRes.rows as any)._array || (nodesRes.rows as any) || [];

        // 找出文本中提到的实体
        const mentionedNodeIds = allNodes
          .filter((n: any) => allReferenceText.includes(n.name))
          .map((n: any) => n.id);

        if (mentionedNodeIds.length > 0) {
          // 2. 拉取关联的边 (一跳关系)
          // 🔑 隐私修复: 必须确保边关联的 doc_id 在授权列表内，或属于全局知识 (doc_id IS NULL 假设为通用知识，视具体需求而定)
          // 鉴于 schema 中 kg_edges 有 doc_id 字段，我们必须应用过滤。
          // 如果是一般知识点推理（非文档归属），doc_id 可能为空，这里我们保留 doc_id IS NULL 的边（通用常识）
          // 并明确允许 authorizedDocIds 中的边。

          let docFilterClause = 'e.doc_id IS NULL'; // 默认允许无归属的通用边
          const authDocList = Array.from(authorizedDocIds || []);

          if (enableDocs && !isGlobal && authDocList.length > 0) {
            const docPlaceholders = authDocList.map(() => '?').join(',');
            docFilterClause += ` OR e.doc_id IN (${docPlaceholders})`;
          } else if (enableDocs && isGlobal) {
            // Global 模式下允许所有文档边
            docFilterClause = '1=1';
          }

          const placeholders = mentionedNodeIds.map(() => '?').join(',');

          //构建完整的参数数组
          let queryParams = [...mentionedNodeIds];
          if (enableDocs && !isGlobal && authDocList.length > 0) {
            queryParams = [...queryParams, ...authDocList];
          }

          const edgesRes = await db.execute(
            `SELECT e.*, n1.name as source_name, n2.name as target_name 
             FROM kg_edges e
             JOIN kg_nodes n1 ON e.source_id = n1.id
             JOIN kg_nodes n2 ON e.target_id = n2.id
             WHERE (source_id IN (${placeholders}) OR target_id IN (${placeholders}))
             AND (${docFilterClause})
             LIMIT 20`,
            queryParams
          );

          const edges = (edgesRes.rows as any)._array || (edgesRes.rows as any) || [];

          if (edges.length > 0) {
            const kgLines = edges.map((e: any) =>
              `- ${e.source_name} --[${e.relation}]--> ${e.target_name}`
            );
            kgContext = `\nKnowledge Graph Insight (知识图谱关联):\n${kgLines.join('\n')}\n`;
            console.log(`[MemoryManager] KG Retrieval found ${edges.length} relations in ${Date.now() - kgStartTime}ms`);
          }
        }
      } catch (e) {
        console.warn('[MemoryManager] KG Retrieval failed:', e);
      }
    }

    // ===== 阶段 5: JIT Micro-Graph Integration (实时补全) =====
    let jitContext = '';
    if (jitPromise) {
      try {
        // Smart Wait: 给予 JIT 一定的宽限期，避免阻塞主流程
        onProgress?.('kg_searching', 95, 'KG_SCAN');
        const jitResult = await jitPromise;
        if (jitResult && jitResult.context) {
          jitContext = `\nDynamically Discovered Relations (实时关联补充):\n${jitResult.context}\n`;
        }
      } catch (e) {
        console.warn('[MemoryManager] JIT Context injection failed:', e);
      }
    }

    onProgress?.('done', 100);

    return {
      context: `relevant_context_block (参考上下文):\n${contextBlock}\n${kgContext}\n${jitContext}`,
      references,
      metadata,
      billingUsage: {
        ragSystem: rewriteTokenUsage,
        isEstimated: rewriteTokenUsage > 0 && false, 
      },
    };
  }

  /**
   * 检查指定文本块在知识图谱中的密度
   * 如果已有足够的关系，则跳过 JIT
   */
  private static async checkKgDensity(results: SearchResult[]): Promise<boolean> {
    try {
      if (results.length === 0) return true;
      const docIds = results.map(r => r.docId).filter((id): id is string => !!id);
      if (docIds.length === 0) return false;

      const placeholders = docIds.map(() => '?').join(',');
      const res = await db.execute(
        `SELECT COUNT(*) as count FROM kg_edges WHERE doc_id IN (${placeholders})`,
        docIds
      );
      const count = (res.rows?.[0] as any)?.count || 0;
      
      // 阈值：如果这些文档已贡献超过 3 条关系，认为静态图谱已足够，不再 JIT
      return count >= 3;
    } catch (e) {
      return false;
    }
  }

  /**
   * 将一个"对话轮次" (用户消息 + AI 回复) 归档到向量记忆中
   * 🔑 非阻塞实现：任务入队后立即返回，由统一队列异步处理
   */
  static async addTurnToMemory(
    sessionId: string,
    userContent: string,
    aiContent: string,
    userMessageId: string,
    assistantMessageId: string,
  ) {
    if (!userContent || !aiContent) return;

    // 🔑 使用统一的向量化队列进行异步处理
    // 这将任务入队并立即返回，不阻塞 UI
    const { useRagStore } = await import('../../store/rag-store');
    const ragStore = useRagStore.getState();

    // 获取队列实例并入队
    // @ts-ignore - 内部方法访问
    const queue = ragStore._getQueue?.();
    if (queue) {
      await queue.enqueueMemory(
        sessionId,
        userContent,
        aiContent,
        userMessageId,
        assistantMessageId,
      );
      console.log(`[MemoryManager] Memory task enqueued for async processing (session: ${sessionId})`);
    } else {
      console.warn('[MemoryManager] Queue not available, falling back to inline processing');
      // Fallback: 如果队列不可用，使用静默的后台处理
      setTimeout(async () => {
        try {
          await MemoryManager.addTurnToMemoryInline(
            sessionId,
            userContent,
            aiContent,
            userMessageId,
            assistantMessageId,
          );
        } catch (e) {
          console.error('[MemoryManager] Fallback archiving failed:', e);
        }
      }, 100);
    }
  }

  /**
   * 内联归档方法 (Fallback 或内部调用)
   * 🔑 私有方法：仅在队列不可用时作为降级方案使用
   */
  private static async addTurnToMemoryInline(
    sessionId: string,
    userContent: string,
    aiContent: string,
    userMessageId: string,
    assistantMessageId: string,
  ) {
    // 🛡️ Sanitize: Strip massive Base64 image data to prevent TextSplitter recursion overflow
    const sanitize = (text: string) =>
      text.replace(/!\[(.*?)\]\(data:image\/.*?;base64,.*?\)/g, '[Image: $1]');

    userContent = sanitize(userContent);
    aiContent = sanitize(aiContent);

    const apiStore = useApiStore.getState();
    const settings = useSettingsStore.getState();
    const preferredModelId = settings.defaultEmbeddingModel;

    let provider: any;
    let modelId: string | undefined;

    // 优先级1: 用户显式选择的 Embedding 模型
    if (preferredModelId) {
      provider = apiStore.providers.find(
        (p) =>
          p.enabled &&
          p.models.some(
            (m: any) => (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled,
          ),
      );

      if (provider) {
        const model = provider.models.find(
          (m: any) => m.uuid === preferredModelId || m.id === preferredModelId,
        );
        modelId = model?.id;
      }
    }

    // 优先级2: Fallback 到第一个可用的 Embedding 模型
    if (!provider || !modelId) {
      provider = apiStore.providers.find(
        (p) => p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding'),
      );

      if (provider) {
        const embeddingModel = provider.models.find(
          (m: any) => m.enabled && m.type === 'embedding',
        );
        modelId = embeddingModel?.id;
      }
    }

    if (!provider || !modelId) return;

    try {
      const now = Date.now();
      await db.execute(
        `INSERT OR IGNORE INTO sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)`,
        [sessionId, 'Shadow Session', now, now],
      );

      const turnText = `User: ${userContent}\nAssistant: ${aiContent}`;
      const ragConfig = settings.globalRagConfig;

      const splitter = new TrigramTextSplitter({
        chunkSize: ragConfig.memoryChunkSize,
        chunkOverlap: ragConfig.chunkOverlap,
      });
      const chunks = await splitter.splitText(turnText);

      const embeddingClient = new EmbeddingClient(provider, modelId);
      const { embeddings, usage } = await embeddingClient.embedDocuments(chunks);

      let archiveTokens = 0;
      let isEstimated = false;

      if (usage) {
        archiveTokens = usage.total_tokens;
      } else {
        archiveTokens = estimateTokens(chunks.join('\n'));
        isEstimated = true;
      }

      try {
        const { useTokenStatsStore } = await import('../../store/token-stats-store');
        useTokenStatsStore.getState().trackUsage({
          modelId: modelId || 'embedding-model',
          usage: {
            ragSystem: { count: archiveTokens, isEstimated },
          },
        });
      } catch (e) {
        console.warn('[MemoryManager] Stats tracking failed', e);
      }

      const vectors = chunks.map((chunk, i) => ({
        sessionId,
        content: chunk,
        embedding: embeddings[i],
        metadata: { type: 'memory', chunkIndex: i },
        startMessageId: userMessageId,
        endMessageId: assistantMessageId,
      }));

      await vectorStore.addVectors(vectors);
      console.log(`[MemoryManager] Inline archiving completed: ${sessionId} (${vectors.length} chunks)`);
    } catch (e) {
      console.error('[MemoryManager] Inline archiving failed:', e);
    }
  }


  /**
   * Manually upsert a single memory item (e.g. manual vectorization of a specific message)
   */
  static async upsertMemory(
    item: {
      id: string;
      content: string;
      sessionId: string;
      role?: string;
      createdAt?: number;
      type?: string;
      usage?: number;
    },
    agentId?: string,
  ) {
    const { content, sessionId, id } = item;
    if (!content) return;

    // Sanitize
    const sanitize = (text: string) =>
      text.replace(/!\[(.*?)\]\(data:image\/.*?;base64,.*?\)/g, '[Image: $1]');
    const cleanContent = sanitize(content);

    const apiStore = useApiStore.getState();
    const settings = useSettingsStore.getState();
    const preferredModelId = settings.defaultEmbeddingModel;

    let provider: any;
    let modelId: string | undefined;

    // Resolve Embedding Model (Same logic as addTurnToMemory)
    if (preferredModelId) {
      provider = apiStore.providers.find(
        (p) =>
          p.enabled &&
          p.models.some(
            (m: any) => (m.uuid === preferredModelId || m.id === preferredModelId) && m.enabled,
          ),
      );
      if (provider) {
        const model = provider.models.find(
          (m: any) => m.uuid === preferredModelId || m.id === preferredModelId,
        );
        modelId = model?.id;
      }
    }

    if (!provider || !modelId) {
      provider = apiStore.providers.find(
        (p) => p.enabled && p.models.some((m: any) => m.enabled && m.type === 'embedding'),
      );
      if (provider) {
        const embeddingModel = provider.models.find(
          (m: any) => m.enabled && m.type === 'embedding',
        );
        modelId = embeddingModel?.id;
      }
    }

    if (!provider || !modelId) throw new Error('No embedding model available');

    try {
      // Ensure session exists
      const now = Date.now();
      await db.execute(
        `INSERT OR IGNORE INTO sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)`,
        [sessionId, 'Shadow Session', now, now],
      );

      // Get configuration
      const chatStore = (await import('../../store/chat-store')).useChatStore.getState();
      const agentStore = (await import('../../store/agent-store')).useAgentStore.getState();
      const session = chatStore.getSession(sessionId);
      const agent = agentStore.getAgent(agentId || session?.agentId || 'default');
      const ragConfig = agent?.ragConfig || settings.globalRagConfig;

      // Split
      const splitter = new TrigramTextSplitter({
        chunkSize: ragConfig.memoryChunkSize,
        chunkOverlap: ragConfig.chunkOverlap,
      });
      const chunks = await splitter.splitText(cleanContent);

      // Embed
      const embeddingClient = new EmbeddingClient(provider, modelId);
      const { embeddings, usage } = await embeddingClient.embedDocuments(chunks);

      // Track Stats
      let archiveTokens = usage ? usage.total_tokens : estimateTokens(chunks.join('\n'));
      try {
        const { useTokenStatsStore } = await import('../../store/token-stats-store');
        useTokenStatsStore.getState().trackUsage({
          modelId: modelId || 'embedding-model',
          usage: {
            ragSystem: { count: archiveTokens, isEstimated: !usage },
          },
        });
      } catch (e) { /* ignore */ }

      // Save Vectors
      const vectors = chunks.map((chunk, i) => ({
        sessionId,
        content: chunk,
        embedding: embeddings[i],
        metadata: { type: 'memory', chunkIndex: i, role: item.role },
        startMessageId: id, // Link to this specific message
        endMessageId: id,
      }));

      await vectorStore.addVectors(vectors);
      console.log(`[MemoryManager] Upserted memory for message ${id}`);
    } catch (e) {
      console.error('[MemoryManager] Upsert failed:', e);
      throw e;
    }
  }
}
