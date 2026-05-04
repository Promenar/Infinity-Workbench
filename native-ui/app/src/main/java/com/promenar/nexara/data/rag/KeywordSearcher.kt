package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity

class KeywordSearcher(
    private val vectorDao: VectorDao
) {
    data class SearchOptions(
        val sessionId: String? = null,
        val docIds: Set<String>? = null,
        val excludeDocs: Boolean = false
    )

    suspend fun search(
        query: String,
        limit: Int = 5,
        options: SearchOptions = SearchOptions()
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val effectiveQuery = if (query.length > 60) query.substring(0, 60) else query

        return try {
            ftsSearch(effectiveQuery, limit, options)
        } catch (e: Exception) {
            fallbackLikeSearch(effectiveQuery, limit, options)
        }
    }

    private suspend fun ftsSearch(
        query: String,
        limit: Int,
        options: SearchOptions
    ): List<SearchResult> {
        val rows = when {
            options.excludeDocs -> vectorDao.searchFtsExcludeDocs(query)
            options.docIds != null && options.docIds.isNotEmpty() && options.docIds.size < 100 ->
                vectorDao.searchFtsByDocIds(query, options.docIds.toList())
            options.sessionId != null -> vectorDao.searchFtsBySession(query, options.sessionId)
            else -> vectorDao.searchFts(query)
        }

        var candidates = rows.map { row -> rowToSearchResult(row, 1.0f) }

        if (options.docIds != null && options.docIds.size >= 100) {
            candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
        }

        return candidates.take(limit)
    }

    private suspend fun fallbackLikeSearch(
        query: String,
        limit: Int,
        options: SearchOptions
    ): List<SearchResult> {
        val keywords = query.split(Regex("\\s+")).filter { it.length > 1 }
        if (keywords.isEmpty()) return emptyList()

        val rows = vectorDao.getAll()
        var candidates = rows.mapNotNull { row ->
            val contentLower = row.content.lowercase()
            var score = 0f
            for (kw in keywords) {
                if (contentLower.contains(kw.lowercase())) score += 1.0f
            }
            if (score > 0f) rowToSearchResult(row, score) else null
        }

        if (options.sessionId != null) {
            candidates = candidates.filter { it.sessionId == options.sessionId }
        }
        if (options.excludeDocs) {
            candidates = candidates.filter { it.docId == null }
        } else if (options.docIds != null && options.docIds.isNotEmpty()) {
            if (options.docIds.size < 100) {
                candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
            } else {
                candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
            }
        }

        return candidates.sortedByDescending { it.similarity }.take(limit)
    }

    private fun rowToSearchResult(row: VectorEntity, similarity: Float): SearchResult {
        return SearchResult(
            id = row.id,
            docId = row.docId,
            sessionId = row.sessionId,
            content = row.content,
            embedding = FloatArray(0),
            metadata = row.metadata,
            startMessageId = row.startMessageId,
            endMessageId = row.endMessageId,
            createdAt = row.createdAt,
            similarity = similarity
        )
    }
}
