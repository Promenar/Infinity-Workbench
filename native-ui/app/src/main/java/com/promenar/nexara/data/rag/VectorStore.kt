package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID

class VectorStore(
    private val vectorDao: VectorDao,
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao
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
        filter: SearchFilter = SearchFilter(),
        onWarning: ((String) -> Unit)? = null
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

        return searchInMemory(queryEmbedding, rows, threshold, limit, onWarning)
    }

    private fun searchInMemory(
        queryEmbedding: FloatArray,
        rows: List<VectorEntity>,
        threshold: Float,
        limit: Int,
        onWarning: ((String) -> Unit)? = null
    ): List<SearchResult> {
        val candidates = mutableListOf<SearchResult>()
        val queryDim = queryEmbedding.size
        val queryMag = magnitude(queryEmbedding)
        var dimensionMismatchCount = 0
        var belowThresholdCount = 0

        for (row in rows) {
            val vec = fromBlob(row.embedding)
            if (vec.size != queryDim) {
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
            } else {
                belowThresholdCount++
            }
        }

        // P0 诊断增强: 记录完整的过滤统计，帮助定位 "0 results" 根因
        if (dimensionMismatchCount > 0 || belowThresholdCount > 0 || candidates.isEmpty()) {
            val storedDim = rows.firstOrNull()?.embedding?.let { fromBlob(it).size }
            val sb = StringBuilder()
            sb.append("[VectorStore] searchInMemory: loaded=${rows.size} rows")
            sb.append(", queryDim=$queryDim")
            sb.append(", storedDim=${storedDim ?: "N/A"}")
            sb.append(", threshold=$threshold")
            sb.append(", dimMismatch=$dimensionMismatchCount")
            sb.append(", belowThreshold=$belowThresholdCount")
            sb.append(", candidates=${candidates.size}")
            if (dimensionMismatchCount > 0) {
                sb.append(" ⚠️ DIM_MISMATCH — stored v${storedDim} vs query v${queryDim} — 模型切换? 需要重新向量化!")
            }
            if (belowThresholdCount > 0 && rows.isNotEmpty()) {
                sb.append(" ⚠️ BELOW_THRESHOLD — $belowThresholdCount rows below cutoff $threshold")
            }
            if (candidates.isEmpty() && rows.isEmpty()) {
                sb.append(" ⚠️ EMPTY_TABLE — DB has 0 matching rows for this filter")
            }
            val msg = sb.toString()
            Log.w("VectorStore", msg)
            onWarning?.invoke(msg)
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

    // ── P0 诊断方法: 支持 MemoryManager 精准定位 "0 results" 根因 ──

    /** vectors 表总行数 */
    suspend fun getTotalVectorCount(): Int = vectorDao.getCount()

    /** 指定 session 下的向量数量 */
    suspend fun getSessionVectorCount(sessionId: String): Int {
        return try {
            vectorDao.getBySessionId(sessionId).size
        } catch (_: Exception) { -1 }
    }

    /** 获取第一条存储向量的维度，用于跨检查询向量是否匹配 */
    suspend fun getFirstStoredDimension(): Int? {
        return try {
            val rows = vectorDao.getAll()
            rows.firstOrNull()?.embedding?.let { fromBlob(it).size }
        } catch (_: Exception) { null }
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
