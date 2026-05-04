package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import java.nio.ByteBuffer
import java.util.UUID

class VectorStore(
    private val vectorDao: VectorDao,
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao,
    private val documentDao: DocumentDao
) {
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

    suspend fun addVectors(vectors: List<VectorRecord>) {
        val entities = vectors.map { vec ->
            VectorEntity(
                id = vec.id,
                docId = vec.docId,
                sessionId = vec.sessionId,
                content = vec.content,
                embedding = toBlob(vec.embedding),
                metadata = vec.metadata,
                startMessageId = vec.startMessageId,
                endMessageId = vec.endMessageId,
                createdAt = vec.createdAt
            )
        }
        vectorDao.insertAll(entities)
    }

    suspend fun addVectorRecords(
        vectors: List<NewVectorRecord>
    ) {
        val now = System.currentTimeMillis()
        val entities = vectors.map { vec ->
            VectorEntity(
                id = UUID.randomUUID().toString(),
                docId = vec.docId,
                sessionId = vec.sessionId,
                content = vec.content,
                embedding = toBlob(vec.embedding),
                metadata = vec.metadata,
                startMessageId = vec.startMessageId,
                endMessageId = vec.endMessageId,
                createdAt = now
            )
        }
        vectorDao.insertAll(entities)
    }

    data class NewVectorRecord(
        val docId: String? = null,
        val sessionId: String? = null,
        val content: String,
        val embedding: FloatArray,
        val metadata: String? = null,
        val startMessageId: String? = null,
        val endMessageId: String? = null
    )

    data class SearchFilter(
        val docId: String? = null,
        val docIds: List<String>? = null,
        val sessionId: String? = null,
        val type: String? = null
    )

    suspend fun search(
        queryEmbedding: FloatArray,
        limit: Int = 5,
        threshold: Float = 0.7f,
        filter: SearchFilter = SearchFilter()
    ): List<SearchResult> {
        val rows = when {
            filter.docIds != null && filter.docIds.isNotEmpty() -> {
                if (filter.type != null) {
                    vectorDao.getByTypeAndDocIds(filter.type, filter.docIds)
                } else {
                    vectorDao.getByDocIds(filter.docIds)
                }
            }
            filter.docId != null -> vectorDao.getByDocId(filter.docId)
            filter.sessionId != null && filter.type != null -> vectorDao.getBySessionIdAndType(filter.sessionId, filter.type)
            filter.sessionId != null -> vectorDao.getBySessionId(filter.sessionId)
            filter.type != null -> vectorDao.getByType(filter.type)
            else -> vectorDao.getAll()
        }

        return searchInMemory(queryEmbedding, rows, threshold, limit)
    }

    private fun searchInMemory(
        queryEmbedding: FloatArray,
        rows: List<VectorEntity>,
        threshold: Float,
        limit: Int
    ): List<SearchResult> {
        val candidates = mutableListOf<SearchResult>()
        val queryMag = magnitude(queryEmbedding)
        var dimensionMismatchCount = 0

        for (row in rows) {
            val vec = fromBlob(row.embedding)
            if (vec.size != queryEmbedding.size) {
                dimensionMismatchCount++
                continue
            }
            val similarity = cosineSimilarity(queryEmbedding, vec, queryMag)
            if (similarity >= threshold) {
                candidates.add(
                    SearchResult(
                        id = row.id,
                        docId = row.docId,
                        sessionId = row.sessionId,
                        content = row.content,
                        embedding = vec,
                        metadata = row.metadata,
                        startMessageId = row.startMessageId,
                        endMessageId = row.endMessageId,
                        createdAt = row.createdAt,
                        similarity = similarity
                    )
                )
            }
        }

        candidates.sortByDescending { it.similarity }
        return candidates.take(limit)
    }

    private fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray, magA: Float = magnitude(vecA)): Float {
        if (vecA.size != vecB.size) return 0f
        var dot = 0f
        var magB = 0f
        for (i in vecA.indices) {
            dot += vecA[i] * vecB[i]
            magB += vecB[i] * vecB[i]
        }
        magB = kotlin.math.sqrt(magB)
        if (magA == 0f || magB == 0f) return 0f
        return dot / (magA * magB)
    }

    private fun magnitude(vec: FloatArray): Float {
        var sum = 0f
        for (v in vec) sum += v * v
        return kotlin.math.sqrt(sum)
    }

    suspend fun deleteDocumentVectors(docId: String) {
        vectorDao.deleteByDocId(docId)
    }

    suspend fun clearSessionMemory(sessionId: String) {
        vectorDao.deleteBySessionId(sessionId)
    }

    suspend fun clearAllVectors() {
        vectorDao.deleteAll()
    }

    suspend fun getKnowledgeGraphStats(): Pair<Int, Int> {
        return Pair(kgNodeDao.getCount(), kgEdgeDao.getCount())
    }

    suspend fun pruneOrphanSessions(activeSessionIds: List<String>) {
        if (activeSessionIds.isEmpty()) {
            vectorDao.deleteAllMemoryVectors()
            kgEdgeDao.deleteAllSessionEdges()
            kgNodeDao.deleteAllSessionNodes()
            return
        }
        vectorDao.deleteOrphanBySessionIds(activeSessionIds)
        kgEdgeDao.deleteAllSessionEdges()
        kgNodeDao.deleteOrphanNodes()
    }

    suspend fun pruneOrphanDocumentKG(activeDocIds: List<String>): Pair<Int, Int> {
        val edgesDeleted = if (activeDocIds.isEmpty()) {
            kgEdgeDao.deleteAllDocEdges()
        } else {
            kgEdgeDao.deleteEdgesNotInDocIds(activeDocIds)
        }
        val nodesDeleted = kgNodeDao.deleteOrphanNodes()
        return Pair(edgesDeleted, nodesDeleted)
    }

    companion object {
        fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
            if (vecA.size != vecB.size) return 0f
            var dot = 0f
            var magA = 0f
            var magB = 0f
            for (i in vecA.indices) {
                dot += vecA[i] * vecB[i]
                magA += vecA[i] * vecA[i]
                magB += vecB[i] * vecB[i]
            }
            magA = kotlin.math.sqrt(magA)
            magB = kotlin.math.sqrt(magB)
            if (magA == 0f || magB == 0f) return 0f
            return dot / (magA * magB)
        }
    }
}
