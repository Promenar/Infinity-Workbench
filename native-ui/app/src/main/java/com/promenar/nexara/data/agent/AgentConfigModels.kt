package com.promenar.nexara.data.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentRagConfig(
    val docChunkSize: Int = 800,
    val chunkOverlap: Int = 100,
    val memoryChunkSize: Int = 1000,
    val summaryTemplate: String = "",
    val currentPreset: String? = null
)

@Serializable
data class AgentRetrievalConfig(
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val enableRerank: Boolean = true,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val enableQueryRewrite: Boolean = true,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteCount: Int = 3,
    val enableHybridSearch: Boolean = true,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f,
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,
    val enableKnowledgeGraph: Boolean = false,
    val queryRewriteModel: String? = null,
    val kgExtractionModel: String? = null,
    val kgExtractionPrompt: String? = null,
    val kgEntityTypes: List<String> = emptyList(),
    val kgFreeMode: Boolean = false,
    val kgDomainAuto: Boolean = false,
    val kgDomainHint: String? = null,
    val kgExtractionTimeoutSeconds: Int = 120,
    val jitMaxChunks: Int = 0
)
