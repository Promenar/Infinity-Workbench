package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import kotlinx.serialization.json.Json
import java.util.UUID

class GraphStore(
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upsertNode(
        name: String,
        type: String = "concept",
        metadata: String? = null,
        scope: KgScope? = null,
        sourceType: String = "full"
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val existing = kgNodeDao.getByName(name)
        if (existing != null) {
            val mergedMetadata = mergeMetadata(existing.metadata, metadata)
            val updatedType = resolveType(existing.type, type)
            val finalSourceType = resolveSourceType(existing.sourceType, sourceType)

            kgNodeDao.updateTypeMetadata(
                id = existing.id,
                type = updatedType,
                metadata = mergedMetadata,
                updatedAt = now,
                sourceType = finalSourceType
            )
            return existing.id
        }

        val entity = KgNodeEntity(
            id = id,
            name = name,
            type = type,
            metadata = metadata ?: "{}",
            sessionId = scope?.sessionId,
            agentId = scope?.agentId,
            sourceType = sourceType,
            createdAt = now,
            updatedAt = now
        )

        val insertResult = kgNodeDao.insert(entity)
        return if (insertResult > 0) {
            id
        } else {
            val retryExisting = kgNodeDao.getByName(name)
            retryExisting?.id ?: id
        }
    }

    suspend fun updateNode(id: String, updates: KgNodeUpdate) {
        val existing = kgNodeDao.getById(id) ?: return
        val updated = existing.copy(
            name = updates.name ?: existing.name,
            type = updates.type ?: existing.type,
            updatedAt = System.currentTimeMillis()
        )
        kgNodeDao.update(updated)
    }

    suspend fun deleteNode(id: String) {
        kgNodeDao.deleteById(id)
    }

    suspend fun mergeNodes(sourceId: String, targetName: String) {
        val target = kgNodeDao.getByName(targetName) ?: throw IllegalArgumentException("Target node '$targetName' not found")
        val targetId = target.id

        if (sourceId == targetId) return

        kgEdgeDao.updateSourceId(sourceId, targetId)
        kgEdgeDao.updateTargetId(sourceId, targetId)
        kgEdgeDao.deleteSelfLoops(targetId)

        val source = kgNodeDao.getById(sourceId)
        if (source != null) {
            val mergedMeta = mergeMetadata(target.metadata, source.metadata)
            kgNodeDao.updateTypeMetadata(
                id = targetId,
                type = target.type,
                metadata = mergedMeta,
                updatedAt = System.currentTimeMillis(),
                sourceType = target.sourceType
            )
        }

        kgNodeDao.deleteById(sourceId)
    }

    suspend fun createEdge(
        sourceId: String,
        targetId: String,
        relation: String,
        docId: String? = null,
        weight: Double = 1.0,
        scope: KgScope? = null,
        sourceType: String = "full"
    ): String {
        val sessionId = scope?.sessionId
        val existing = kgEdgeDao.findEdge(sourceId, targetId, relation, docId, sessionId)

        if (existing != null) {
            val newWeight = existing.weight + weight
            val finalSourceType = resolveSourceType(existing.sourceType, sourceType)
            kgEdgeDao.updateWeight(existing.id, newWeight, System.currentTimeMillis(), finalSourceType)
            return existing.id
        }

        val id = UUID.randomUUID().toString()
        val entity = KgEdgeEntity(
            id = id,
            sourceId = sourceId,
            targetId = targetId,
            relation = relation,
            weight = weight,
            docId = docId,
            sessionId = sessionId,
            agentId = scope?.agentId,
            sourceType = sourceType,
            createdAt = System.currentTimeMillis()
        )
        kgEdgeDao.insert(entity)
        return id
    }

    suspend fun updateEdge(id: String, updates: KgEdgeUpdate) {
        val existing = kgEdgeDao.getById(id) ?: return
        val updated = existing.copy(
            relation = updates.relation ?: existing.relation,
            weight = updates.weight ?: existing.weight
        )
        kgEdgeDao.insert(updated)
    }

    suspend fun deleteEdge(id: String) {
        kgEdgeDao.deleteById(id)
    }

    suspend fun getAllNodes(): List<KgNode> {
        return kgNodeDao.getAll().map { it.toModel() }
    }

    suspend fun getEdgesForNode(nodeId: String): List<KgEdge> {
        return kgEdgeDao.getByNodeId(nodeId).map { it.toModel() }
    }

    suspend fun getGraphData(
        docIds: List<String>? = null,
        sessionId: String? = null,
        agentId: String? = null
    ): GraphData {
        val edges = when {
            docIds != null && docIds.isNotEmpty() -> kgEdgeDao.getByDocIds(docIds)
            sessionId != null -> kgEdgeDao.getBySessionId(sessionId)
            else -> kgEdgeDao.getAllDocEdges() // For global, get all edges with doc_id
        }

        val nodeIds = edges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
        val nodes = if (nodeIds.isNotEmpty()) {
            kgNodeDao.getByIds(nodeIds.toList()).map { it.toModel() }
        } else {
            emptyList()
        }

        return GraphData(
            nodes = nodes,
            edges = edges.map { it.toModel() }
        )
    }

    private fun mergeMetadata(existingMeta: String?, newMeta: String?): String {
        val existing = try {
            existingMeta?.let { Json.decodeFromString<MutableMap<String, kotlinx.serialization.json.JsonElement>>(it) } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }

        if (newMeta == null) return Json.encodeToString(kotlinx.serialization.serializer<Map<String, kotlinx.serialization.json.JsonElement>>(), existing)

        val newMap = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(newMeta)
        } catch (e: Exception) {
            return Json.encodeToString(kotlinx.serialization.serializer<Map<String, kotlinx.serialization.json.JsonElement>>(), existing)
        }

        for ((key, value) in newMap) {
            existing[key] = value
        }

        return Json.encodeToString(kotlinx.serialization.serializer<Map<String, kotlinx.serialization.json.JsonElement>>(), existing)
    }

    private fun resolveType(existingType: String, newType: String): String {
        val knownTypes = setOf("concept", "person", "org", "location", "event", "product")
        val existingIsKnown = knownTypes.contains(existingType.lowercase())
        val newIsKnown = knownTypes.contains(newType.lowercase())

        return when {
            existingIsKnown && !newIsKnown -> newType
            !existingIsKnown && newIsKnown -> existingType
            else -> newType
        }
    }

    private fun resolveSourceType(existing: String, new: String): String {
        val priority = mapOf("full" to 2, "summary" to 1, "jit" to 0)
        val existingPriority = priority[existing] ?: 0
        val newPriority = priority[new] ?: 0
        return if (newPriority >= existingPriority) new else existing
    }

    private fun KgNodeEntity.toModel() = KgNode(
        id = id,
        name = name,
        type = type,
        metadata = metadata,
        sourceType = sourceType,
        createdAt = createdAt
    )

    private fun KgEdgeEntity.toModel() = KgEdge(
        id = id,
        sourceId = sourceId,
        targetId = targetId,
        relation = relation,
        weight = weight,
        docId = docId,
        sourceType = sourceType,
        createdAt = createdAt
    )
}

data class KgScope(
    val sessionId: String? = null,
    val agentId: String? = null,
    val messageId: String? = null
)

data class KgNodeUpdate(
    val name: String? = null,
    val type: String? = null
)

data class KgEdgeUpdate(
    val relation: String? = null,
    val weight: Double? = null
)

data class GraphData(
    val nodes: List<KgNode>,
    val edges: List<KgEdge>
)
