package com.promenar.infinity.data.mapper

import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.domain.model.SearchResult
import com.promenar.infinity.domain.model.SearchSource

fun VectorEntity.toSearchResult(
    score: Double,
    source: SearchSource = SearchSource.VECTOR
): SearchResult = SearchResult(
    chunkId = id,
    documentId = docId,
    documentTitle = "",
    chunkText = content,
    score = score,
    source = source
)
