package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import kotlinx.coroutines.CancellationException

enum class KeywordSearchMode { Fts, LikeFallback }

data class KeywordSearchOutcome(
    val results: List<SearchResult>,
    val mode: KeywordSearchMode,
    val technical: String? = null,
)

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
    ): List<SearchResult> = searchWithOutcome(query, limit, options).results

    suspend fun searchWithOutcome(
        query: String,
        limit: Int = 5,
        options: SearchOptions = SearchOptions(),
    ): KeywordSearchOutcome {
        if (query.isBlank()) return KeywordSearchOutcome(emptyList(), KeywordSearchMode.Fts)
        val effectiveQuery = if (query.length > 60) query.substring(0, 60) else query

        return try {
            KeywordSearchOutcome(
                results = ftsSearch(effectiveQuery, limit, options),
                mode = KeywordSearchMode.Fts,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            KeywordSearchOutcome(
                results = fallbackLikeSearch(effectiveQuery, limit, options),
                mode = KeywordSearchMode.LikeFallback,
                technical = failure::class.simpleName?.take(80),
            )
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
