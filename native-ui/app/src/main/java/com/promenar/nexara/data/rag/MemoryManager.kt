package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.utils.NexaraLogger

class MemoryManager(
    private val vectorStore: VectorStore,
    private val keywordSearcher: KeywordSearcher,
    private val graphStore: GraphStore,
    private val embeddingClient: EmbeddingClient,
    private val rerankClient: RerankClient? = null,
    private val queryRewriter: QueryRewriter? = null,
    val ragConfig: RagConfiguration = RagConfiguration()  // 改为 public 以支持 ContextBuilder 读取全局 KG 开关
) {
    data class RetrieveOptions(
        val enableMemory: Boolean = true,
        val enableDocs: Boolean = true,
        val activeDocIds: List<String> = emptyList(),
        /** 当前数据层尚不能安全展开文件夹；保留范围信息，且文件夹本身绝不能触发全库回退。 */
        val activeFolderIds: List<String> = emptyList(),
        val isGlobal: Boolean = false,
        val sessionId: String = "",
        val enableRerank: Boolean = false,  // 从 RagOptions 传递，结合 ragConfig.enableRerank 共同决定
        val configOverride: RagConfiguration? = null
    )

    data class RetrieveResult(
        val context: String,
        val references: List<RagReference>,
        val metadata: RetrieveMetadata
    )

    data class RetrieveMetadata(
        val searchTimeMs: Long = 0,
        val rerankTimeMs: Long = 0,
        val recallCount: Int = 0,
        val finalCount: Int = 0,
        val sourceDistribution: SourceDistribution = SourceDistribution()
    )

    data class SourceDistribution(
        val memory: Int = 0,
        val documents: Int = 0
    )

    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: RetrieveOptions = RetrieveOptions(),
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
    ): RetrieveResult {
        val TAG = "MemoryManager"
        NexaraLogger.log("[$TAG] retrieveContext start: query='${query.take(60)}', session=$sessionId, " +
            "enableMemory=${options.enableMemory}, enableDocs=${options.enableDocs}, isGlobal=${options.isGlobal}, " +
            "enableRerank=${options.enableRerank}, activeDocIds=${options.activeDocIds.size}")

        val startTime = System.currentTimeMillis()
        val effectiveConfig = options.configOverride ?: ragConfig

        val hasSpecificDocs = options.activeDocIds.isNotEmpty()
        val authorizedDocIds = if (options.enableDocs && !options.isGlobal && hasSpecificDocs) {
            options.activeDocIds.toSet()
        } else null

        // 非全局检索必须具有显式文档范围。activeFolderIds 尚未安全展开时按零文档处理，
        // 禁止把空/未解析范围解释为“搜索全部文档”。
        val canSearchDocs = options.enableDocs && (options.isGlobal || authorizedDocIds != null)
        val canSearchMemory = options.enableMemory
        // Rerank 决策: 用户开关 (RagOptions.enableRerank) AND 配置门 (ragConfig.enableRerank) 两者都开才执行
        val canRerank = options.enableRerank && effectiveConfig.enableRerank && rerankClient != null
        NexaraLogger.log("[$TAG] canRerank=$canRerank (options.enableRerank=${options.enableRerank} && effectiveConfig.enableRerank=${effectiveConfig.enableRerank} && rerankClient=${if (rerankClient != null) "configured" else "null"})")

        NexaraLogger.log("[$TAG] canSearch: memory=$canSearchMemory docs=$canSearchDocs hasSpecificDocs=$hasSpecificDocs authorizedDocIds=${authorizedDocIds?.size ?: "null"}")

        if (!canSearchMemory && !canSearchDocs) {
            NexaraLogger.log("[$TAG] No search enabled, returning empty")
            return emptyResult(0)
        }

        if (!embeddingClient.isConfigured && !embeddingClient.hasLocalFallback) {
            NexaraLogger.log("[$TAG] embedding unavailable, skip retrieval: ${embeddingClient.diagnosticMessage().lineSequence().firstOrNull().orEmpty()}")
            return emptyResult(System.currentTimeMillis() - startTime)
        }

        onProgress?.invoke("Embedding query", 10, "Sending query to embedding model")

        // P0 诊断日志: 记录 vectors 表状态，帮助定位 "0 results" 的根本原因
        try {
            val totalVecCount = vectorStore.getTotalVectorCount()
            val sessionVecCount = vectorStore.getSessionVectorCount(sessionId)
            NexaraLogger.log("[$TAG] vectors DB state: total=$totalVecCount, sessionVecCount=$sessionVecCount, memoryThreshold=${effectiveConfig.memoryThreshold}, docThreshold=${effectiveConfig.docThreshold}, rerankTopK=${effectiveConfig.rerankTopK}")
        } catch (e: Exception) {
            NexaraLogger.log("[$TAG] vectors DB state query failed: ${e.message?.take(80)}")
        }

        val queryVariants = buildQueryVariants(query, effectiveConfig)
        val queryEmbeddings = mutableListOf<Pair<String, FloatArray>>()
        var firstEmbedMs = 0L
        for ((index, searchQuery) in queryVariants.withIndex()) {
            try {
                val startEmbed = System.currentTimeMillis()
                val emb = embeddingClient.embedQuery(searchQuery).first
                val embedMs = System.currentTimeMillis() - startEmbed
                if (index == 0) firstEmbedMs = embedMs
                val storedDim = try { vectorStore.getFirstStoredDimension() } catch (_: Exception) { null }
                NexaraLogger.log("[$TAG] embedQuery success: variant=${index + 1}/${queryVariants.size}, dim=${emb.size}, time=${embedMs}ms, storedDim=${storedDim ?: "N/A (DB empty?)"}")
                queryEmbeddings.add(searchQuery to emb)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] embedQuery failed for variant=${index + 1}", e)
            }
        }
        if (queryEmbeddings.isEmpty()) {
            return emptyResult(System.currentTimeMillis() - startTime)
        }
        onProgress?.invoke("Embedding done", 25, "${queryEmbeddings.first().second.size}d vector in ${firstEmbedMs}ms")

        onProgress?.invoke("Searching memory", 30, "Retrieving from vector DB")
        val results = mutableListOf<SearchResult>()

        if (canSearchMemory) {
            try {
                val startMem = System.currentTimeMillis()
                val memoryResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.search(
                        queryEmbedding = queryEmbedding,
                        limit = effectiveConfig.rerankTopK,
                        threshold = effectiveConfig.memoryThreshold,
                        filter = VectorStore.SearchFilter(
                            sessionId = if (options.isGlobal) null else sessionId,
                            type = "memory"
                        ),
                        onWarning = { warn -> NexaraLogger.log("[$TAG] memory search warning: $warn") }
                    )
                }
                val memMs = System.currentTimeMillis() - startMem
                NexaraLogger.log("[$TAG] memory search: ${memoryResults.size} results, time=${memMs}ms, threshold=${effectiveConfig.memoryThreshold}${if (memoryResults.isEmpty()) " ⚠️ 0 results — check: session has vectors? dimensions match? similarity ≥ threshold?" else ""}")
                results.addAll(memoryResults)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] searchMemory failed", e)
            }

            try {
                val summaryResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.search(
                        queryEmbedding = queryEmbedding,
                        limit = if (effectiveConfig.enableRerank) 10 else 5,
                        threshold = effectiveConfig.memoryThreshold - 0.05f,
                        filter = VectorStore.SearchFilter(
                            sessionId = if (options.isGlobal) null else sessionId,
                            type = "summary"
                        ),
                        onWarning = { warn -> NexaraLogger.log("[$TAG] summary search warning: $warn") }
                    )
                }
                NexaraLogger.log("[$TAG] summary search: ${summaryResults.size} results, threshold=${effectiveConfig.memoryThreshold - 0.05f}")
                results.addAll(summaryResults)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] searchSummary failed", e)
            }
        }

        if (canSearchDocs) {
            onProgress?.invoke("Searching documents", 50, "Scanning all indexed documents")
            try {
                val startDocs = System.currentTimeMillis()
                val docResults = queryEmbeddings.flatMap { (_, queryEmbedding) ->
                    vectorStore.search(
                        queryEmbedding = queryEmbedding,
                        limit = effectiveConfig.rerankTopK,
                        threshold = effectiveConfig.docThreshold,
                        filter = VectorStore.SearchFilter(
                            type = "document",
                            docIds = authorizedDocIds?.toList()
                        )
                    )
                }
                val docsMs = System.currentTimeMillis() - startDocs
                NexaraLogger.log("[$TAG] document search: ${docResults.size} results, time=${docsMs}ms, threshold=${effectiveConfig.docThreshold}")
                results.addAll(docResults)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] searchDocs failed", e)
            }
        }

        NexaraLogger.log("[$TAG] total raw results before fusion: ${results.size}")

        // Hybrid search (RRF Fusion)
        onProgress?.invoke("Hybrid fusion", 70, "Merging vector + keyword results")
        val finalResults = if (effectiveConfig.enableHybridSearch) {
            try {
                val keywordResults = queryVariants.flatMap { searchQuery ->
                    keywordSearcher.search(
                        query = searchQuery,
                        limit = effectiveConfig.rerankTopK,
                        options = KeywordSearcher.SearchOptions(
                            sessionId = if (options.isGlobal) null else sessionId,
                            docIds = authorizedDocIds,
                            excludeDocs = !canSearchDocs
                        )
                    )
                }.filter { result ->
                    if (result.docId != null) {
                        canSearchDocs && (options.isGlobal || result.docId in authorizedDocIds.orEmpty())
                    } else {
                        canSearchMemory && (options.isGlobal || result.sessionId == sessionId)
                    }
                }
                val fused = rrfFusion(results, keywordResults, effectiveConfig)
                NexaraLogger.log("[$TAG] hybrid fusion: ${results.size} vector + ${keywordResults.size} keyword → ${fused.size} fused")
                fused
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] hybrid fusion failed, using raw results", e)
                results
            }
        } else {
            results
        }

        onProgress?.invoke("Ranking results", 90, "${finalResults.size} candidates found")
        if (finalResults.isEmpty()) {
            NexaraLogger.log("[$TAG] No results after fusion, returning empty")
            return emptyResult(System.currentTimeMillis() - startTime)
        }

        val uniqueResults = finalResults
            .sortedByDescending { it.similarity }
            .distinctBy { it.id }

        var actualRerankTimeMs = 0L
        val rerankedResults = if (canRerank && uniqueResults.isNotEmpty()) {
            NexaraLogger.log("[$TAG] rerank start: ${uniqueResults.size} candidates")
            onProgress?.invoke("Reranking", 92, "Re-scoring ${uniqueResults.size} candidates")
            val rerankStart = System.currentTimeMillis()
            try {
                rerankClient.rerank(query, uniqueResults, effectiveConfig.rerankTopK)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] rerank failed", e)
                uniqueResults
            }.also {
                actualRerankTimeMs = System.currentTimeMillis() - rerankStart
                onProgress?.invoke("Rerank complete", 95, "${it.size} results in ${actualRerankTimeMs}ms")
            }
        } else {
            uniqueResults
        }

        // P1: 若重排已启用，rerankFinalK 作为最终结果总数的上限（语义上优于 memoryLimit+docLimit 简单加和）
        val finalCap = if (canRerank) effectiveConfig.rerankFinalK
            else effectiveConfig.memoryLimit + effectiveConfig.docLimit

        val topMemories = rerankedResults
            .filter { parseTypeFromMetadata(it.metadata) == "memory" }
            .take(effectiveConfig.memoryLimit)
        val topDocs = rerankedResults
            .filter { parseTypeFromMetadata(it.metadata) == "doc" }
            .take(effectiveConfig.docLimit)
        val combinedResults = (topMemories + topDocs)
            .sortedByDescending { it.similarity }
            .take(finalCap)

        val endTime = System.currentTimeMillis()
        val retrievedChunks = combinedResults.joinToString("\n\n") { r ->
            val typeLabel = if (parseTypeFromMetadata(r.metadata) == "memory") "Memory" else "Document"
            "[$typeLabel]: ${r.content}"
        }
        val contextBlock = if (effectiveConfig.summaryTemplate.isNotBlank()) {
            effectiveConfig.summaryTemplate
                .replace("{retrieved_chunks}", retrievedChunks)
                .replace("{query}", query)
        } else {
            retrievedChunks
        }

        val references = combinedResults.map { r ->
            val isMemory = parseTypeFromMetadata(r.metadata) == "memory"
            val sourceLabel = when {
                isMemory -> "对话记忆"
                r.docId != null -> {
                    documentReferenceSource(r.metadata, r.docId)
                }
                else -> "文档片段"
            }
            RagReference(
                id = r.id,
                content = r.content,
                source = sourceLabel,
                score = r.similarity,
                documentId = r.docId
            )
        }

        return RetrieveResult(
            context = "relevant_context_block (参考上下文):\n$contextBlock",
            references = references,
            metadata = RetrieveMetadata(
                searchTimeMs = endTime - startTime,
                rerankTimeMs = actualRerankTimeMs,
                recallCount = uniqueResults.size,
                finalCount = combinedResults.size,
                sourceDistribution = SourceDistribution(
                    memory = combinedResults.count { parseTypeFromMetadata(it.metadata) == "memory" },
                    documents = combinedResults.count { parseTypeFromMetadata(it.metadata) == "doc" }
                )
            )
        )
    }

    suspend fun addTurnToMemory(
        sessionId: String,
        userContent: String,
        aiContent: String,
        userMessageId: String,
        assistantMessageId: String
    ) {
        if (userContent.isBlank() || aiContent.isBlank()) {
            NexaraLogger.log("[MemoryManager] addTurnToMemory skipped: empty content for session=$sessionId")
            return
        }

        val startTime = System.currentTimeMillis()
        val sanitizedUser = sanitizeContent(userContent)
        val sanitizedAi = sanitizeContent(aiContent)

        val turnText = "User: $sanitizedUser\nAssistant: $sanitizedAi"
        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.memoryChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(turnText)
        if (chunks.isEmpty()) {
            NexaraLogger.log("[MemoryManager] addTurnToMemory: 0 chunks after split, session=$sessionId")
            return
        }

        NexaraLogger.log("[MemoryManager] addTurnToMemory: chunking=${chunks.size} chunks, session=$sessionId, chunkSize=${ragConfig.memoryChunkSize}")
        val embeddingResult = embeddingClient.embedDocuments(chunks)
        NexaraLogger.log("[MemoryManager] addTurnToMemory: embedding done, dim=${embeddingResult.embeddings.firstOrNull()?.size}, chunks=${chunks.size}")

        val vectors = chunks.mapIndexed { i, chunk ->
            VectorStore.NewVectorRecord(
                sessionId = sessionId,
                content = chunk,
                embedding = embeddingResult.embeddings[i],
                metadata = """{"type":"memory","chunkIndex":$i}""",
                startMessageId = userMessageId,
                endMessageId = assistantMessageId
            )
        }

        vectorStore.addVectorRecords(vectors)
        val totalMs = System.currentTimeMillis() - startTime
        NexaraLogger.log("[MemoryManager] addTurnToMemory done: ${vectors.size} vectors stored, session=$sessionId, total=${totalMs}ms")
    }

    private fun rrfFusion(
        vectorResults: List<SearchResult>,
        keywordResults: List<SearchResult>,
        config: RagConfiguration
    ): List<SearchResult> {
        val rrfK = 60
        val scoreMap = mutableMapOf<String, Float>()
        val nodeMap = mutableMapOf<String, SearchResult>()
        val alpha = config.hybridAlpha
        val bm25Boost = config.hybridBM25Boost

        val addScores = { items: List<SearchResult>, weight: Float ->
            items.forEachIndexed { rank, item ->
                val current = scoreMap[item.id] ?: 0f
                scoreMap[item.id] = current + weight * (1.0f / (rrfK + rank + 1))
                if (!nodeMap.containsKey(item.id)) nodeMap[item.id] = item
            }
        }

        val uniqueVector = vectorResults.distinctBy { it.id }.sortedByDescending { it.similarity }
        addScores(uniqueVector, alpha)
        addScores(keywordResults, (1 - alpha) * bm25Boost)

        val rrfMax = (1.0f / (rrfK + 1)) * 2
        val fusedResults = scoreMap.mapNotNull { (id, score) ->
            nodeMap[id]?.let { node ->
                var normalized = score / rrfMax
                normalized = normalized.coerceIn(0.01f, 0.99f)
                node.copy(similarity = normalized)
            }
        }

        return fusedResults.sortedByDescending { it.similarity }
    }

    private suspend fun buildQueryVariants(query: String, config: RagConfiguration): List<String> {
        val normalizedCount = config.queryRewriteCount.coerceIn(0, 5)
        if (!config.enableQueryRewrite || normalizedCount == 0 || queryRewriter == null) {
            return listOf(query)
        }
        return try {
            val strategy = parseRewriteStrategy(config.queryRewriteStrategy)
            val result = queryRewriter.rewrite(
                query = query,
                count = normalizedCount,
                strategyOverride = strategy,
                modelIdOverride = config.queryRewriteModel
            )
            result.variants
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(normalizedCount + 1)
                .ifEmpty { listOf(query) }
                .also { variants ->
                    NexaraLogger.log("[MemoryManager] query rewrite: enabled strategy=${config.queryRewriteStrategy}, variants=${variants.size}")
                }
        } catch (e: Exception) {
            NexaraLogger.logError("[MemoryManager] query rewrite failed, fallback to original query", e)
            listOf(query)
        }
    }

    private fun parseRewriteStrategy(raw: String): RewriteStrategy {
        return when (raw.trim().lowercase()) {
            "hyde" -> RewriteStrategy.HYDE
            "expansion", "expand" -> RewriteStrategy.EXPANSION
            else -> RewriteStrategy.MULTI_QUERY
        }
    }

    private fun sanitizeContent(text: String): String {
        return text.replace(Regex("!\\[.*?\\]\\(data:image/.*?;base64,.*?\\)"), "[Image]")
    }

    private fun parseTypeFromMetadata(metadata: String?): String? {
        val type = vectorMetadataType(metadata)
        return if (type == "document") "doc" else type
    }

    private fun emptyResult(searchTimeMs: Long) = RetrieveResult(
        context = "",
        references = emptyList(),
        metadata = RetrieveMetadata(searchTimeMs = searchTimeMs)
    )
}
