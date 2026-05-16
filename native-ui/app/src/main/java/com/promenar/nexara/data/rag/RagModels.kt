package com.promenar.nexara.data.rag

import kotlinx.serialization.Serializable

@Serializable
data class VectorRecord(
    val id: String,
    val docId: String? = null,
    val sessionId: String? = null,
    val content: String,
    val embedding: FloatArray,
    val metadata: String? = null,
    val startMessageId: String? = null,
    val endMessageId: String? = null,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorRecord
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Serializable
data class SearchResult(
    val id: String,
    val docId: String? = null,
    val sessionId: String? = null,
    val content: String,
    val embedding: FloatArray = FloatArray(0),
    val metadata: String? = null,
    val startMessageId: String? = null,
    val endMessageId: String? = null,
    val createdAt: Long,
    val similarity: Float,
    val originalSimilarity: Float? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SearchResult
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

@Serializable
data class RagConfiguration(
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,
    val enableKnowledgeGraph: Boolean = false,
    val enableQueryRewrite: Boolean = true,
    val enableHybridSearch: Boolean = true,
    val enableRerank: Boolean = false,
    val enableIncrementalHash: Boolean = true,
    val enableLocalPreprocess: Boolean = false,
    val currentPreset: String? = null,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteModel: String? = null,
    val queryRewriteCount: Int = 3,
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val docChunkSize: Int = 800,
    val chunkOverlap: Int = 100,
    val memoryChunkSize: Int = 1000,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f,
    val kgExtractionModel: String? = null,
    val kgExtractionPrompt: String? = null,
    val kgEntityTypes: List<String> = emptyList(),
    val kgFreeMode: Boolean = false,
    val kgDomainAuto: Boolean = false,
    val kgDomainHint: String? = null,
    val costStrategy: String = "on-demand",
    val trackRetrievalMetrics: Boolean = false,
    val showRetrievalProgress: Boolean = true,
    val showRetrievalDetails: Boolean = true,
    val jitMaxChunks: Int = 0,
    val jitTimeoutMs: Long = 5000,
    val jitMaxCharsPerChunk: Int = 2000,
    val jitCacheTTL: Long = 3600,
    val summaryTemplate: String = "System: You are given the following retrieved chunks:\n\n{retrieved_chunks}\n\nBased on these, answer the user query.\n\nUser Query: {query}",
    // === Embed 模型设置 ===
    /** Embedding 向量维度，null=使用模型默认（如 text-embedding-3-small=1536）。
     *  某些模型支持降维输出，可显式指定。 */
    val embedDimension: Int? = null,
    /** Embedding API 单次调用最大 token 数（影响每批发送的切块数）。
     *  不同模型上限不同（如 512/2048/8191）；默认 8192 适用于大多数现代模型。 */
    val maxEmbedTokensPerCall: Int = 8192,
    // === Rerank 模型设置 ===
    /** Rerank 模型单次调用最大文档数，超过则自动分批。
     *  大多数 rerank 模型限制在 32-100 篇之间；默认 100 适应主流服务。 */
    val rerankMaxPerCall: Int = 100
)

@Serializable
data class EmbeddingResult(
    val embeddings: List<FloatArray>,
    val usage: EmbeddingUsage? = null
)

@Serializable
data class EmbeddingUsage(
    val totalTokens: Int
)

@Serializable
data class RewriteResult(
    val variants: List<String>,
    val usage: RewriteUsage? = null
)

@Serializable
data class RewriteUsage(
    val input: Int,
    val output: Int,
    val total: Int
)

@Serializable
enum class RewriteStrategy {
    HYDE, MULTI_QUERY, EXPANSION
}

@Serializable
data class KgNode(
    val id: String,
    val name: String,
    val type: String = "concept",
    val metadata: String? = null,
    val sourceType: String = "full",
    val createdAt: Long
)

@Serializable
data class KgEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val weight: Double = 1.0,
    val docId: String? = null,
    val sourceType: String = "full",
    val createdAt: Long
)

@Serializable
data class ExtractionResult(
    val nodes: List<ExtractedNode>,
    val edges: List<ExtractedEdge>,
    val error: String? = null
)

@Serializable
data class ExtractedNode(
    val name: String,
    val type: String,
    val metadata: String? = null
)

@Serializable
data class ExtractedEdge(
    val source: String,
    val target: String,
    val relation: String,
    val weight: Double = 1.0
)

@Serializable
data class MicroGraphResult(
    val nodes: List<ExtractedNode>,
    val edges: List<ExtractedEdge>,
    val context: String,
    val sourceChunkIds: List<String>,
    val query: String,
    val extractedAt: Long
)

@Serializable
data class VectorizationTask(
    val id: String,
    val type: String,
    val docId: String? = null,
    val docTitle: String? = null,
    val sessionId: String? = null,
    val userContent: String? = null,
    val aiContent: String? = null,
    val userMessageId: String? = null,
    val assistantMessageId: String? = null,
    var status: String = "pending",
    var progress: Double = 0.0,
    var totalChunks: Int? = null,
    var lastChunkIndex: Int = 0,
    var error: String? = null,
    var subStatus: String? = null,
    var kgStrategy: String? = null,
    var skipVectorization: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class RerankResult(
    val index: Int,
    val relevanceScore: Float,
    val document: RerankDocument
)

@Serializable
data class RerankDocument(
    val text: String
)

@Serializable
data class VectorStats(
    val total: Int,
    val byType: VectorTypeStats,
    val bySession: List<SessionVectorCount>,
    val redundancyRate: Float,
    val storageSizeMb: Float
)

@Serializable
data class VectorTypeStats(
    var memory: Int = 0,
    var summary: Int = 0,
    var doc: Int = 0
)

@Serializable
data class SessionVectorCount(
    val sessionId: String,
    val count: Int
)
