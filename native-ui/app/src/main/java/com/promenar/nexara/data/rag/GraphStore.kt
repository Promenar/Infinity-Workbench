package com.promenar.nexara.data.rag

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.KgIdentity
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.*
import kotlinx.serialization.json.*
import java.util.UUID

/** 实体全局共享，所有来源归属均由证据表达；业务唯一约束与 Room 事务共同保证并发幂等。 */
class GraphStore(internal val database: NexaraDatabase) {
    suspend fun getGraphSubgraph(request: GraphSubgraphRequest = GraphSubgraphRequest()): GraphSubgraph =
        GraphSubgraphStore(database).load(request)

    suspend fun searchGraphEntities(request: GraphEntitySearchRequest): List<KgNode> =
        GraphSubgraphStore(database).search(request)

    /** 按最近证据时间返回最多 1,000 个会话。 */
    suspend fun getSessionOptions(): List<KgSessionOption> = KgSessionGraphStore(database).getSessionOptions()

    /** 返回最早的完整轮次前缀，最多 400 轮、20,000 条实体出现记录。 */
    suspend fun getSessionTurns(sessionId: String): List<KgSessionTurn> = KgSessionGraphStore(database).getSessionTurns(sessionId)

    /** 两类来源分别限额，硬上限各 100 条；非正限额返回空来源。 */
    suspend fun getEntitySources(nodeId: String, limit: Int = 20): KgEntitySources =
        KgSessionGraphStore(database).getEntitySources(nodeId, limit)

    private val nodes get() = database.kgNodeDao()
    private val edges get() = database.kgEdgeDao()
    private val evidence get() = database.kgEvidenceDao()

    suspend fun upsertNode(name: String, type: String = "concept", metadata: String? = null,
        scope: KgScope? = null, sourceType: String = "full"): String = database.withTransaction {
        putNode(name, type, metadata, source(scope, null, sourceType))
    }

    private suspend fun putNode(name: String, type: String, metadata: String?, source: KgSource): String {
        val normalized = KgIdentity.normalize(name)
        val normalizedType = KgIdentity.normalize(type).ifBlank { "concept" }
        require(normalized.isNotBlank() && normalized.length <= 512 && normalizedType.length <= 512) { "实体身份无效" }
        val now = System.currentTimeMillis()
        nodes.insert(KgNodeEntity(UUID.randomUUID().toString(), KgIdentity.display(name), normalizedType, metadata,
            now, now, normalized))
        val stored = requireNotNull(nodes.findIdentity(normalized, normalizedType))
        nodes.update(stored.copy(metadata = mergeMetadata(stored.metadata, metadata), updatedAt = now))
        addEvidence(source, nodeId = stored.id)
        return stored.id
    }

    suspend fun createEdge(sourceId: String, targetId: String, relation: String, docId: String? = null,
        weight: Double = 1.0, scope: KgScope? = null, sourceType: String = "full"): String = database.withTransaction {
        putEdge(sourceId, targetId, relation, weight, source(scope, docId, sourceType))
    }

    private suspend fun putEdge(sourceId: String, targetId: String, relation: String, weight: Double, source: KgSource): String {
        require(nodes.getRawById(sourceId) != null && nodes.getRawById(targetId) != null) { "关系端点不存在" }
        val normalized = KgIdentity.normalize(relation)
        require(normalized.isNotBlank() && normalized.length <= 512 && weight.isFinite() && weight in 0.0..1.0) { "关系或权重无效" }
        edges.insert(KgEdgeEntity(UUID.randomUUID().toString(), sourceId, targetId, KgIdentity.display(relation), weight,
            System.currentTimeMillis(), normalized))
        val stored = requireNotNull(edges.findIdentity(sourceId, targetId, normalized))
        if (weight > stored.weight) edges.update(stored.copy(weight = weight))
        addEvidence(source, edgeId = stored.id, weight = weight)
        // 关系本身也证明两个端点存在，不能沿用其它来源的单一节点归属。
        addEvidence(source, nodeId = sourceId)
        addEvidence(source, nodeId = targetId)
        return stored.id
    }

    /** 调用方必须持有候选提交的 hash/epoch 与活动任务准入；内部仍保证整图原子写入。 */
    suspend fun commitCandidate(candidate: DocumentIndexCandidate, source: KgSource) = database.withTransaction {
        val mapping = candidate.nodes.associate { it.id to putNode(it.name, it.type, it.metadata, source) }
        candidate.edges.forEach { edge ->
            putEdge(requireNotNull(mapping[edge.sourceId]) { "候选关系缺少源节点" },
                requireNotNull(mapping[edge.targetId]) { "候选关系缺少目标节点" }, edge.relation, edge.weight, source)
        }
    }

    /**
     * 以一轮会话图谱抽取结果替换该轮已有证据。所属助手消息已不存在（抽取期间被删除）时不写入并返回 false。
     */
    suspend fun replaceTurn(sessionId: String, messageId: String, contentHash: String, graph: ValidatedGraph): Boolean =
        database.withTransaction {
            val message = database.messageDao().getById(messageId)
            if (message == null || message.sessionId != sessionId) return@withTransaction false
            val previous = evidence.affectedByMessage(messageId)
            evidence.deleteMessageEvidence(messageId)
            val source = KgSource("session", sessionId = sessionId, contentHash = contentHash, messageId = messageId)
            val ids = graph.nodes.associate { it.name to putNode(it.name, it.type, it.metadata, source) }
            graph.edges.forEach { putEdge(ids.getValue(it.source), ids.getValue(it.target), it.relation, it.weight, source) }
            evidence.collectAffected(previous)
            true
        }

    suspend fun captureJitSources(results: List<SearchResult>): List<KgJitSource> = database.withTransaction {
        results.groupBy { it.docId }.map { (docId, chunks) ->
            if (docId == null) KgJitSource(null, kgSha256(chunks.joinToString("\u0000") { it.content }), null)
            else {
                val file = database.fileEntryDao().findActiveByUuid(docId)?.takeUnless { it.inRecycleBin || it.isDirectory }
                // 无法核验文档版本时只返回即时结果，不把未知来源写成会话知识。
                val matches = file != null && chunks.all { chunk ->
                    val expected = chunk.documentContentHash ?: documentContentHashFromVectorMetadata(chunk.metadata)
                    if (expected != null) expected == file.hash else {
                        val vector = database.vectorDao().getById(chunk.id)
                        vector?.docId == docId && vector.content == chunk.content &&
                            (vector.updatedAt ?: vector.createdAt) >= file.updatedAt
                    }
                }
                KgJitSource(docId, file?.hash.orEmpty(), file?.updatedAt?.takeIf { matches })
            }
        }
    }

    /** 缓存与证据在同一事务内提交，删除、回收或版本变化后的后台结果不得重新落库。 */
    suspend fun persistJit(result: MicroGraphResult, sources: List<KgJitSource>, sessionId: String,
        cache: KgJitCacheEntity): Boolean = database.withTransaction {
        if (sources.isEmpty()) return@withTransaction false
        for (source in sources) {
            val docId = source.docId ?: continue
            val file = database.fileEntryDao().findActiveByUuid(docId)
            if (source.epoch == null || file == null || file.inRecycleBin || file.hash != source.contentHash || file.updatedAt != source.epoch) {
                return@withTransaction false
            }
        }
        database.kgJitCacheDao().deleteExpired(System.currentTimeMillis())
        database.kgJitCacheDao().insert(cache)
        evidence.insertCacheSources(sources.mapNotNull { source ->
            source.docId?.let { KgJitCacheSourceEntity(cache.cacheKey, it, source.contentHash) }
        })
        sources.forEach { input ->
            val source = KgSource("jit", input.docId, sessionId, input.contentHash, cache.cacheKey, input.docId != null)
            val graph = ValidatedGraph.from(result.nodes, result.edges)
            val ids = graph.nodes.associate { it.name to putNode(it.name, it.type, it.metadata, source) }
            graph.edges.forEach { putEdge(ids.getValue(it.source), ids.getValue(it.target), it.relation, it.weight, source) }
        }
        true
    }

    suspend fun updateNode(id: String, updates: KgNodeUpdate) = database.withTransaction {
        val current = nodes.getById(id) ?: return@withTransaction
        val name = KgIdentity.display(updates.name ?: current.name)
        val type = KgIdentity.normalize(updates.type ?: current.type)
        require(name.isNotBlank() && name.length <= 512 && type.isNotBlank() && type.length <= 512)
        val target = nodes.findIdentity(KgIdentity.normalize(name), type)
        if (target != null && target.id != id) mergeInto(id, target)
        else nodes.update(current.copy(name = name, type = type, normalizedName = KgIdentity.normalize(name), updatedAt = System.currentTimeMillis()))
    }

    suspend fun mergeNodes(sourceId: String, targetName: String) = database.withTransaction {
        val source = nodes.getById(sourceId) ?: return@withTransaction
        val target = nodes.findIdentity(KgIdentity.normalize(targetName), source.type)
            ?: throw IllegalArgumentException("目标实体不存在或类型不一致")
        if (sourceId != target.id) mergeInto(sourceId, target)
    }

    private suspend fun mergeInto(sourceId: String, target: KgNodeEntity) {
        evidence.getForNode(sourceId).forEach { copyEvidence(it, nodeId = target.id) }
        edges.getRawByNodeId(sourceId).forEach { edge ->
            val source = if (edge.sourceId == sourceId) target.id else edge.sourceId
            val destination = if (edge.targetId == sourceId) target.id else edge.targetId
            val existing = edges.findIdentity(source, destination, edge.normalizedRelation)
            if (existing == null || existing.id == edge.id) edges.update(edge.copy(sourceId = source, targetId = destination))
            else {
                evidence.getForEdges(listOf(edge.id)).forEach { copyEvidence(it, edgeId = existing.id) }
                edges.update(existing.copy(weight = maxOf(existing.weight, edge.weight)))
                edges.deleteById(edge.id)
            }
        }
        val original = nodes.getRawById(sourceId)
        nodes.update(target.copy(metadata = mergeMetadata(target.metadata, original?.metadata), updatedAt = System.currentTimeMillis()))
        nodes.deleteById(sourceId)
    }

    private suspend fun copyEvidence(row: KgEvidenceEntity, nodeId: String? = null, edgeId: String? = null) {
        addEvidence(KgSource(row.sourceType, row.docId, row.sessionId, row.contentHash, row.taskId, row.workspaceDocument, row.messageId), nodeId, edgeId, row.createdAt, row.weight)
    }

    suspend fun deleteNode(id: String) = database.withTransaction {
        edges.getRawByNodeId(id).forEach { edges.deleteById(it.id) }
        nodes.deleteById(id)
        evidence.collectNodes()
    }

    suspend fun updateEdge(id: String, updates: KgEdgeUpdate) = database.withTransaction {
        val current = edges.getById(id) ?: return@withTransaction
        val relation = KgIdentity.display(updates.relation ?: current.relation)
        val weight = updates.weight ?: current.weight
        require(relation.isNotBlank() && relation.length <= 512 && weight.isFinite() && weight in 0.0..1.0)
        // 手动编辑作用于这条关系当前全部来源；重新归一关系时逐条保留来源置信度。
        if (updates.weight != null) evidence.setEdgeWeights(id, weight)
        val target = edges.findIdentity(current.sourceId, current.targetId, KgIdentity.normalize(relation))
        if (target != null && target.id != id) {
            evidence.getForEdges(listOf(id)).forEach { copyEvidence(it, edgeId = target.id) }
            edges.update(target.copy(weight = maxOf(target.weight, weight)))
            edges.deleteById(id)
        } else edges.update(current.copy(relation = relation, normalizedRelation = KgIdentity.normalize(relation), weight = weight))
    }

    suspend fun clearGraphForDoc(docId: String) { evidence.clearDocument(docId) }
    suspend fun deleteEdge(id: String) = database.withTransaction { edges.deleteById(id); evidence.collectNodes(); Unit }
    suspend fun getAllNodes(): List<KgNode> = nodes.getAll().map { it.toModel() }
    suspend fun getEdgesForNode(nodeId: String): List<KgEdge> = database.withTransaction { modelEdges(edges.getByNodeId(nodeId)) }

    suspend fun getGraphData(docIds: List<String>? = null, sessionId: String? = null): GraphData = database.withTransaction {
        val selectedEdges = when {
            docIds != null -> edges.getByDocIds(docIds)
            sessionId != null -> edges.getBySessionId(sessionId)
            else -> edges.getAll()
        }
        val selectedNodes = when {
            docIds != null -> nodes.getByDocIds(docIds)
            sessionId != null -> nodes.getBySessionId(sessionId)
            else -> nodes.getAll()
        }
        GraphData(selectedNodes.map { it.toModel() }, modelEdges(selectedEdges, docIds, sessionId))
    }

    suspend fun getDocumentOptions(): List<KgDocumentOption> = edges.getActiveDocumentOptions().map { KgDocumentOption(it.docId, it.title) }

    private suspend fun modelEdges(rows: List<KgEdgeEntity>, docIds: List<String>? = null, sessionId: String? = null): List<KgEdge> {
        val sources = rows.chunked(400).flatMap { evidence.getActiveForEdges(it.map { edge -> edge.id }) }.groupBy { it.edgeId }
        return rows.map { edge ->
            val selected = sources[edge.id].orEmpty().filter { (docIds == null || it.docId in docIds) && (sessionId == null || it.sessionId == sessionId) }
            KgEdge(edge.id, edge.sourceId, edge.targetId, edge.relation, selected.mapNotNull { it.weight }.maxOrNull() ?: edge.weight,
                selected.mapNotNull { it.docId }.distinct().singleOrNull(),
                if (selected.any { it.sourceType == "document" }) "full" else selected.firstOrNull()?.sourceType ?: "legacy", edge.createdAt,
                selected.mapNotNull { it.docId }.distinct().sorted())
        }
    }

    private suspend fun addEvidence(source: KgSource, nodeId: String? = null, edgeId: String? = null, createdAt: Long = System.currentTimeMillis(), weight: Double? = null) {
        require((nodeId == null) != (edgeId == null))
        // 消息 id 只在存在时参与证据身份，既有证据的 id 保持不变。
        val key = Json.encodeToString(ListSerializer(), listOf(nodeId.orEmpty(), edgeId.orEmpty(), source.type,
            source.docId.orEmpty(), source.sessionId.orEmpty(), source.contentHash, source.taskId) + listOfNotNull(source.messageId))
        evidence.insert(KgEvidenceEntity(kgSha256(key), nodeId, edgeId, source.type, source.docId, source.sessionId,
            source.contentHash, source.taskId, createdAt, source.workspaceDocument, weight, source.messageId))
        if (weight != null) evidence.increaseWeight(kgSha256(key), weight)
    }

    private fun source(scope: KgScope?, docId: String?, type: String): KgSource {
        val document = docId ?: scope?.docId
        return KgSource(when { type == "jit" -> "jit"; document != null -> "document"; scope?.sessionId != null -> "session"; else -> "legacy" },
            document, scope?.sessionId, scope?.contentHash.orEmpty(), scope?.taskId.orEmpty(), scope?.workspaceDocument ?: false,
            scope?.messageId.takeIf { document == null })
    }

    private fun mergeMetadata(old: String?, added: String?): String {
        fun parse(value: String?) = runCatching { Json.parseToJsonElement(value.orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return JsonObject(parse(old) + parse(added)).toString()
    }

    private fun KgNodeEntity.toModel() = KgNode(id, name, type, metadata, "full", createdAt)
    private fun ListSerializer() = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
}

data class KgDocumentOption(val docId: String, val title: String)
data class KgSource(val type: String, val docId: String? = null, val sessionId: String? = null,
    val contentHash: String = "", val taskId: String = "", val workspaceDocument: Boolean = false,
    /** 会话证据所属轮次的助手消息；消息删除后证据不可见。 */
    val messageId: String? = null)
data class KgJitSource(val docId: String?, val contentHash: String, val epoch: Long?)
data class KgScope(val sessionId: String? = null, val agentId: String? = null, val messageId: String? = null,
    val docId: String? = null, val contentHash: String = "", val taskId: String = "", val workspaceDocument: Boolean = false)
data class KgNodeUpdate(val name: String? = null, val type: String? = null)
data class KgEdgeUpdate(val relation: String? = null, val weight: Double? = null)
data class GraphData(val nodes: List<KgNode>, val edges: List<KgEdge>)
