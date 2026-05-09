package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.manager.KgProvider

class MicroGraphKgAdapter(
    private val microGraphExtractor: MicroGraphExtractor
) : KgProvider {
    override suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): String? {
        val searchResults = topKResults.map { ref ->
            SearchResult(
                id = ref.id,
                content = ref.content,
                similarity = ref.score,
                createdAt = System.currentTimeMillis()
            )
        }
        val result = microGraphExtractor.extract(
            topKResults = searchResults,
            query = query,
            sessionId = sessionId
        )
        return result?.context
    }
}
