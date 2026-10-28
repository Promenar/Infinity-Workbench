package com.promenar.infinity.domain.repository

import com.promenar.infinity.domain.model.SearchFilters
import com.promenar.infinity.domain.model.SearchResult
import com.promenar.infinity.domain.model.VectorChunk

data class VectorTypeCount(val type: String, val count: Int)
data class VectorSessionCount(val sessionId: String, val count: Int)
data class MemoryVectorRecord(
    val id: String,
    val content: String,
    val sessionId: String?,
    val createdAt: Long
)

interface IVectorRepository {
    suspend fun search(query: String, topK: Int, filters: SearchFilters): List<SearchResult>
    suspend fun index(documentId: String, chunks: List<VectorChunk>)
    suspend fun deleteByDocument(documentId: String)
    suspend fun deleteVector(id: String)
    suspend fun deleteAll()
    suspend fun getCount(): Int
    suspend fun countByType(): List<VectorTypeCount>
    suspend fun countBySession(limit: Int = 10): List<VectorSessionCount>
    suspend fun getMemoryVectors(limit: Int = 100): List<MemoryVectorRecord>
}
