package com.promenar.nexara.data.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentRagConfig(
    val docChunkSize: Float = 800f,
    val chunkOverlap: Float = 100f,
    val memoryChunkSize: Float = 1000f,
    val contextWindow: Int = 20,
    val summaryThreshold: Int = 10,
    val summaryTemplate: String = ""
)

@Serializable
data class AgentRetrievalConfig(
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val enableRerank: Boolean = false,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val enableQueryRewrite: Boolean = false,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteCount: Int = 3,
    val enableHybridSearch: Boolean = false,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f
)
