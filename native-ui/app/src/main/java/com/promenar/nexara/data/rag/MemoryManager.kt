package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MemoryManager(
    private val vectorStore: VectorStore,
    private val keywordSearcher: KeywordSearcher,
    private val graphStore: GraphStore,
    private val embeddingClient: EmbeddingClient,
    private val rerankClient: RerankClient? = null,
    val ragConfig: RagConfiguration = RagConfiguration()  // 改为 public 以支持 ContextBuilder 读取全局 KG 开关
) {
    data class RetrieveOptions(
        val enableMemory: Boolean = true,
        val enableDocs: Boolean = true,
        val activeDocIds: List<String> = emptyList(),
        val isGlobal: Boolean = false,
        val sessionId: String = "",
        val enableRerank: Boolean = false  // 从 RagOptions 传递，结合 ragConfig.enableRerank 共同决定
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

        onProgress?.invoke("Embedding query", 10, "Sending query to embedding model")
        val startTime = System.currentTimeMillis()
        val effectiveConfig = ragConfig

        // Q1 修复: 当 enableDocs=true 且未指定特定文档时，视为全局文档检索
        // 原逻辑: isGlobal=false + activeDocIds 为空 → 静默跳过文档检索 (bug)
        val hasSpecificDocs = options.activeDocIds.isNotEmpty()
        val canSearchAllDocs = options.enableDocs && (options.isGlobal || !hasSpecificDocs)
        val authorizedDocIds = if (options.enableDocs && !options.isGlobal && hasSpecificDocs) {
            options.activeDocIds.toSet()
        } else null

        val canSearchDocs = canSearchAllDocs
        val canSearchMemory = options.enableMemory
        // Rerank 决策: 用户开关 (RagOptions.enableRerank) AND 配置门 (ragConfig.enableRerank) 两者都开才执行
        val canRerank = options.enableRerank && effectiveConfig.enableRerank && rerankClient != null
        NexaraLogger.log("[$TAG] canRerank=$canRerank (options.enableRerank=${options.enableRerank} && effectiveConfig.enableRerank=${effectiveConfig.enableRerank} && rerankClient=${if (rerankClient != null) "configured" else "null"})")

        NexaraLogger.log("[$TAG] canSearch: memory=$canSearchMemory docs=$canSearchDocs hasSpecificDocs=$hasSpecificDocs authorizedDocIds=${authorizedDocIds?.size ?: "null"}")

        if (!canSearchMemory && !canSearchDocs) {
            NexaraLogger.log("[$TAG] No search enabled, returning empty")
            return emptyResult(0)
        }

        // P0 诊断日志: 记录 vectors 表状态，帮助定位 "0 results" 的根本原因
        try {
            val totalVecCount = vectorStore.getTotalVectorCount()
            val sessionVecCount = vectorStore.getSessionVectorCount(sessionId)
            NexaraLogger.log("[$TAG] vectors DB state: total=$totalVecCount, sessionVecCount=$sessionVecCount, memoryThreshold=${effectiveConfig.memoryThreshold}, docThreshold=${effectiveConfig.docThreshold}, rerankTopK=${effectiveConfig.rerankTopK}")
        } catch (e: Exception) {
            NexaraLogger.log("[$TAG] vectors DB state query failed: ${e.message?.take(80)}")
        }

        val queryEmbedding = try {
            val startEmbed = System.currentTimeMillis()
            val emb = embeddingClient.embedQuery(query).first
            val embedMs = System.currentTimeMillis() - startEmbed
            // P0 诊断: 记录查询向量维度 + 可用于跨检存储向量维度
            val storedDim = try { vectorStore.getFirstStoredDimension() } catch (_: Exception) { null }
            NexaraLogger.log("[$TAG] embedQuery success: dim=${emb.size}, time=${embedMs}ms, storedDim=${storedDim ?: "N/A (DB empty?)"}")
            onProgress?.invoke("Embedding done", 25, "${emb.size}d vector in ${embedMs}ms")
            emb
        } catch (e: Exception) {
            NexaraLogger.logError("[$TAG] embedQuery failed", e)
            return emptyResult(System.currentTimeMillis() - startTime)
        }

        onProgress?.invoke("Searching memory", 30, "Retrieving from vector DB")
        val results = mutableListOf<SearchResult>()

        if (canSearchMemory) {
            try {
                val startMem = System.currentTimeMillis()
                val memoryResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = effectiveConfig.rerankTopK,
                    threshold = effectiveConfig.memoryThreshold,
                    filter = VectorStore.SearchFilter(
                        sessionId = if (options.isGlobal) null else sessionId,
                        type = "memory"
                    ),
                    onWarning = { warn -> NexaraLogger.log("[$TAG] memory search warning: $warn") }
                )
                val memMs = System.currentTimeMillis() - startMem
                NexaraLogger.log("[$TAG] memory search: ${memoryResults.size} results, time=${memMs}ms, threshold=${effectiveConfig.memoryThreshold}${if (memoryResults.isEmpty()) " ⚠️ 0 results — check: session has vectors? dimensions match? similarity ≥ threshold?" else ""}")
                results.addAll(memoryResults)
            } catch (e: Exception) {
                NexaraLogger.logError("[$TAG] searchMemory failed", e)
            }

            try {
                val summaryResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = if (effectiveConfig.enableRerank) 10 else 5,
                    threshold = effectiveConfig.memoryThreshold - 0.05f,
                    filter = VectorStore.SearchFilter(
                        sessionId = if (options.isGlobal) null else sessionId,
                        type = "summary"
                    ),
                    onWarning = { warn -> NexaraLogger.log("[$TAG] summary search warning: $warn") }
                )
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
                val docResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = effectiveConfig.rerankTopK,
                    threshold = effectiveConfig.docThreshold,
                    filter = VectorStore.SearchFilter(
                        type = "document",
                        docIds = authorizedDocIds?.toList()
                    )
                )
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
                val keywordResults = keywordSearcher.search(
                    query = query,
                    limit = effectiveConfig.rerankTopK,
                    options = KeywordSearcher.SearchOptions(
                        sessionId = if (options.isGlobal) null else sessionId,
                        docIds = authorizedDocIds,
                        excludeDocs = !options.enableDocs
                    )
                )
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
                    // 从 metadata 提取 fileUuid 作为文档标识
                    val fileUuid = try {
                        kotlinx.serialization.json.Json.parseToJsonElement(r.metadata ?: "{}")
                            .jsonObject["fileUuid"]?.jsonPrimitive?.content?.take(8)
                    } catch (_: Exception) { null }
                    "文档: ${fileUuid ?: r.docId.take(8)}"
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

    private fun sanitizeContent(text: String): String {
        return text.replace(Regex("!\\[.*?\\]\\(data:image/.*?;base64,.*?\\)"), "[Image]")
    }

    private fun parseTypeFromMetadata(metadata: String?): String? {
        if (metadata == null) return null
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(metadata).jsonObject
            val type = json["type"]?.jsonPrimitive?.content
            if (type == "document") "doc" else type
        } catch (e: Exception) {
            null
        }
    }

    private fun emptyResult(searchTimeMs: Long) = RetrieveResult(
        context = "",
        references = emptyList(),
        metadata = RetrieveMetadata(searchTimeMs = searchTimeMs)
    )
}
