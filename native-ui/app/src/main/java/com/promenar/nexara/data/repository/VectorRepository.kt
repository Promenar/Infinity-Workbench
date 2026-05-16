package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.mapper.toSearchResult
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.domain.model.SearchFilters
import com.promenar.nexara.domain.model.SearchResult
import com.promenar.nexara.domain.model.SearchSource
import com.promenar.nexara.domain.model.VectorChunk
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.domain.repository.VectorSessionCount
import com.promenar.nexara.domain.repository.VectorTypeCount
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.sqrt

class VectorRepository(
    private val vectorDao: VectorDao,
    private val embeddingClient: EmbeddingClient
) : IVectorRepository {

    override suspend fun search(
        query: String,
        topK: Int,
        filters: SearchFilters
    ): List<SearchResult> {
        val (queryEmbedding, _) = embeddingClient.embedQuery(query)

        val candidates = when {
            !filters.documentIds.isNullOrEmpty() && filters.sessionId != null ->
                vectorDao.getByDocIds(filters.documentIds)
                    .filter { it.sessionId == filters.sessionId }
            !filters.documentIds.isNullOrEmpty() ->
                vectorDao.getByDocIds(filters.documentIds)
            filters.sessionId != null ->
                vectorDao.getBySessionId(filters.sessionId)
            else ->
                vectorDao.getAll()
        }

        val scored = candidates.mapNotNull { entity ->
            val vec = fromBlob(entity.embedding)
            if (vec.size != queryEmbedding.size) return@mapNotNull null
            val score = cosineSimilarity(queryEmbedding, vec)
            if (score < filters.minScore) return@mapNotNull null
            entity to score
        }

        return scored
            .sortedByDescending { it.second }
            .take(topK)
            .map { (entity, score) ->
                entity.toSearchResult(score.toDouble(), SearchSource.VECTOR)
            }
        // FTS 待集成: 可结合 VectorDao.searchFts 实现混合检索
    }

    override suspend fun index(documentId: String, chunks: List<VectorChunk>) {
        if (chunks.isEmpty()) return

        val texts = chunks.map { it.text }
        val embeddings = embeddingClient.embedDocuments(texts).embeddings

        val entities = chunks.zip(embeddings).map { (chunk, embedding) ->
            VectorEntity(
                id = UUID.randomUUID().toString(),
                docId = documentId,
                content = chunk.text,
                embedding = toBlob(embedding),
                createdAt = System.currentTimeMillis()
            )
        }

        vectorDao.insertAll(entities)
        // FTS 索引由 Room @Fts4(contentEntity = VectorEntity::class) 自动同步
    }

    override suspend fun deleteByDocument(documentId: String) {
        vectorDao.deleteByDocId(documentId)
    }

    override suspend fun deleteVector(id: String) {
        vectorDao.deleteById(id)
    }

    override suspend fun deleteAll() {
        vectorDao.deleteAll()
    }

    override suspend fun getCount(): Int =
        vectorDao.getCount()

    override suspend fun countByType(): List<VectorTypeCount> =
        vectorDao.countByType().map { VectorTypeCount(it.type, it.count) }

    override suspend fun countBySession(limit: Int): List<VectorSessionCount> =
        vectorDao.countBySession(limit).map { VectorSessionCount(it.session_id, it.count) }

    override suspend fun getMemoryVectors(limit: Int): List<MemoryVectorRecord> {
        return vectorDao.getByType("memory")
            .sortedByDescending { it.createdAt }
            .take(limit)
            .map { MemoryVectorRecord(it.id, it.content, it.sessionId, it.createdAt) }
    }

    private fun toBlob(embedding: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * 4)
        buffer.asFloatBuffer().put(embedding)
        return buffer.array()
    }

    private fun fromBlob(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob)
        val floatBuffer = buffer.asFloatBuffer()
        val result = FloatArray(floatBuffer.remaining())
        floatBuffer.get(result)
        return result
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        magA = sqrt(magA)
        magB = sqrt(magB)
        return if (magA == 0f || magB == 0f) 0f else dot / (magA * magB)
    }
}
