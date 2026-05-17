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
            isGlobal = options.isGlobal,
            sessionId = sessionId,
            enableRerank = options.enableRerank  // 传递用户重排开关
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
}
