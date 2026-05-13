package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.SearchFilters
import com.promenar.nexara.domain.model.SearchResult
import com.promenar.nexara.domain.model.VectorChunk

interface IVectorRepository {
    suspend fun search(query: String, topK: Int, filters: SearchFilters): List<SearchResult>
    suspend fun index(documentId: String, chunks: List<VectorChunk>)
    suspend fun deleteByDocument(documentId: String)
    suspend fun getCount(): Int
}
