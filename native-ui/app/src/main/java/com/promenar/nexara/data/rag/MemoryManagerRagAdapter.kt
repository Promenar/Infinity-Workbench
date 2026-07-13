package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import com.promenar.nexara.ui.chat.manager.RagProvider

class MemoryManagerRagAdapter(
    private val memoryManager: MemoryManager
) : RagProvider {

    override suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)?
    ): Triple<String, List<RagReference>, RagUsage?> {
        val retrieveOptions = MemoryManager.RetrieveOptions(
            enableMemory = options.enableMemory,
            enableDocs = options.enableDocs,
            activeDocIds = options.activeDocIds,
            activeFolderIds = options.activeFolderIds,
            isGlobal = options.isGlobal,
            sessionId = sessionId,
            enableRerank = options.enableRerank,  // 传递用户重排开关
            configOverride = buildConfigOverride(options)
        )

        val result = memoryManager.retrieveContext(query, sessionId, retrieveOptions, onProgress)

        val estimatedTokens = if (result.context.isNotEmpty()) {
            (result.context.length / 4).coerceAtLeast(1)
        } else {
            0
        }

        val usage = if (estimatedTokens > 0) {
            RagUsage(ragSystem = estimatedTokens, isEstimated = true)
        } else {
            null
        }

        return Triple(result.context, result.references, usage)
    }

    private fun buildConfigOverride(options: RagOptions): RagConfiguration {
        val base = memoryManager.ragConfig
        return base.copy(
            enableMemory = options.enableMemory,
            enableDocs = options.enableDocs,
            enableKnowledgeGraph = options.enableKnowledgeGraph ?: base.enableKnowledgeGraph,
            enableRerank = options.enableRerank,
            enableQueryRewrite = options.enableQueryRewrite ?: base.enableQueryRewrite,
            enableHybridSearch = options.enableHybridSearch ?: base.enableHybridSearch,
            memoryLimit = options.memoryLimit ?: base.memoryLimit,
            memoryThreshold = options.memoryThreshold ?: base.memoryThreshold,
            docLimit = options.docLimit ?: base.docLimit,
            docThreshold = options.docThreshold ?: base.docThreshold,
            rerankTopK = options.rerankTopK ?: base.rerankTopK,
            rerankFinalK = options.rerankFinalK ?: base.rerankFinalK,
            queryRewriteStrategy = options.queryRewriteStrategy ?: base.queryRewriteStrategy,
            queryRewriteCount = options.queryRewriteCount ?: base.queryRewriteCount,
            hybridAlpha = options.hybridAlpha ?: base.hybridAlpha,
            hybridBM25Boost = options.hybridBM25Boost ?: base.hybridBM25Boost
        )
    }
}
