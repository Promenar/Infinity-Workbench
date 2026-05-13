package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.domain.model.SearchResult
import com.promenar.nexara.domain.model.SearchSource

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
