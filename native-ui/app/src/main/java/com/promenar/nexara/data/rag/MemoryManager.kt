package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MemoryManager(
    private val vectorStore: VectorStore,
    private val keywordSearcher: KeywordSearcher,
    private val graphStore: GraphStore,
    private val embeddingClient: EmbeddingClient,
    private val ragConfig: RagConfiguration = RagConfiguration()
) {
    data class RetrieveOptions(
        val enableMemory: Boolean = true,
        val enableDocs: Boolean = true,
        val activeDocIds: List<String> = emptyList(),
        val isGlobal: Boolean = false,
        val sessionId: String = ""
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
        options: RetrieveOptions = RetrieveOptions()
    ): RetrieveResult {
        val startTime = System.currentTimeMillis()
        val effectiveConfig = ragConfig

        val authorizedDocIds = if (options.enableDocs && !options.isGlobal) {
            options.activeDocIds.toSet().takeIf { it.isNotEmpty() }
        } else null

        val canSearchDocs = options.enableDocs && (options.isGlobal || (authorizedDocIds != null && authorizedDocIds.isNotEmpty()))
        if (!options.enableMemory && !canSearchDocs) {
            return emptyResult(0)
        }

        val queryEmbedding = try {
            embeddingClient.embedQuery(query).first
        } catch (e: Exception) {
            return emptyResult(System.currentTimeMillis() - startTime)
        }

        val results = mutableListOf<SearchResult>()

        if (options.enableMemory) {
            try {
                val memoryResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = effectiveConfig.rerankTopK,
                    threshold = effectiveConfig.memoryThreshold,
                    filter = VectorStore.SearchFilter(
                        sessionId = if (options.isGlobal) null else sessionId,
                        type = "memory"
                    )
                )
                results.addAll(memoryResults)
            } catch (e: Exception) {
                // Continue without memory results
            }

            try {
                val summaryResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = if (effectiveConfig.enableRerank) 10 else 5,
                    threshold = effectiveConfig.memoryThreshold - 0.05f,
                    filter = VectorStore.SearchFilter(
                        sessionId = if (options.isGlobal) null else sessionId,
                        type = "summary"
                    )
                )
                results.addAll(summaryResults)
            } catch (e: Exception) {
                // Continue without summary results
            }
        }

        if (canSearchDocs) {
            try {
                val docResults = vectorStore.search(
                    queryEmbedding = queryEmbedding,
                    limit = effectiveConfig.rerankTopK,
                    threshold = effectiveConfig.docThreshold,
                    filter = VectorStore.SearchFilter(
                        type = "doc",
                        docIds = if (options.isGlobal) null else authorizedDocIds?.toList()
                    )
                )
                results.addAll(docResults)
            } catch (e: Exception) {
                // Continue without doc results
            }
        }

        // Hybrid search (RRF Fusion)
        val finalResults = if (effectiveConfig.enableHybridSearch) {
            val keywordResults = keywordSearcher.search(
                query = query,
                limit = effectiveConfig.rerankTopK,
                options = KeywordSearcher.SearchOptions(
                    sessionId = if (options.isGlobal) null else sessionId,
                    docIds = authorizedDocIds,
                    excludeDocs = !options.enableDocs
                )
            )
            rrfFusion(results, keywordResults, effectiveConfig)
        } else {
            results
        }

        if (finalResults.isEmpty()) {
            return emptyResult(System.currentTimeMillis() - startTime)
        }

        val uniqueResults = finalResults
            .sortedByDescending { it.similarity }
            .distinctBy { it.id }

        val topMemories = uniqueResults
            .filter { parseTypeFromMetadata(it.metadata) == "memory" }
            .take(effectiveConfig.memoryLimit)
        val topDocs = uniqueResults
            .filter { parseTypeFromMetadata(it.metadata) == "doc" }
            .take(effectiveConfig.docLimit)
        val combinedResults = (topMemories + topDocs).sortedByDescending { it.similarity }

        val endTime = System.currentTimeMillis()
        val contextBlock = combinedResults.joinToString("\n\n") { r ->
            val typeLabel = if (parseTypeFromMetadata(r.metadata) == "memory") "Memory" else "Document"
            "[$typeLabel]: ${r.content}"
        }

        val references = combinedResults.map { r ->
            RagReference(
                id = r.id,
                content = r.content,
                source = if (parseTypeFromMetadata(r.metadata) == "memory") "Previous Conversation" else "Unknown Document",
                score = r.similarity,
                documentId = r.docId
            )
        }

        return RetrieveResult(
            context = "relevant_context_block (参考上下文):\n$contextBlock",
            references = references,
            metadata = RetrieveMetadata(
                searchTimeMs = endTime - startTime,
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
        if (userContent.isBlank() || aiContent.isBlank()) return

        val sanitizedUser = sanitizeContent(userContent)
        val sanitizedAi = sanitizeContent(aiContent)

        val turnText = "User: $sanitizedUser\nAssistant: $sanitizedAi"
        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.memoryChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(turnText)
        if (chunks.isEmpty()) return

        val embeddingResult = embeddingClient.embedDocuments(chunks)

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
            json["type"]?.jsonPrimitive?.content
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
