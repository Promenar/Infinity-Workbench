package com.promenar.nexara.data.rag

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.nexara.data.local.db.KgIdentity
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.ACTIVE_KG_EVIDENCE
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class GraphSubgraphRequest(
    val docId: String? = null,
    val conceptOnly: Boolean = false,
    val nodeLimit: Int = DEFAULT_GRAPH_NODE_LIMIT,
    val edgeLimit: Int = DEFAULT_GRAPH_EDGE_LIMIT,
    val retainedNodeIds: List<String> = emptyList(),
    val expandNodeId: String? = null,
)

data class GraphSubgraph(val data: GraphData, val totalNodeCount: Int)

data class GraphEntitySearchRequest(
    val query: String,
    val docId: String? = null,
    val conceptOnly: Boolean = false,
    val limit: Int = 20,
)

const val DEFAULT_GRAPH_NODE_LIMIT = 300
const val DEFAULT_GRAPH_EDGE_LIMIT = 1_200
const val MAX_GRAPH_NODE_LIMIT = 2_000
const val MAX_GRAPH_EDGE_LIMIT = 8_000
const val GRAPH_EXPANSION_BATCH_SIZE = 100

/** 查询阶段限制结果规模；不把完整图谱读入内存后再裁切。 */
class GraphSubgraphStore(private val database: NexaraDatabase) {
    suspend fun load(request: GraphSubgraphRequest): GraphSubgraph = withContext(Dispatchers.IO) {
        database.withTransaction {
            val bounded = request.copy(
                nodeLimit = request.nodeLimit.coerceIn(1, MAX_GRAPH_NODE_LIMIT),
                edgeLimit = request.edgeLimit.coerceIn(1, MAX_GRAPH_EDGE_LIMIT),
            )
            val predicate = activeNode(bounded.docId, bounded.conceptOnly)
            val count = query("SELECT COUNT(*) AS total FROM kg_nodes n WHERE ${predicate.sql}", predicate.args) {
                getLong(getColumnIndexOrThrow("total")).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }.single()
            val selected = linkedMapOf<String, KgNode>()
            if (bounded.retainedNodeIds.isEmpty() && bounded.expandNodeId == null) {
                readNodes(
                    "SELECT n.* FROM kg_nodes n WHERE ${predicate.sql} ORDER BY COALESCE(n.updated_at, n.created_at) DESC, n.id LIMIT ?",
                    predicate.args + bounded.nodeLimit,
                ).forEach { selected[it.id] = it }
            } else {
                val retained = bounded.retainedNodeIds.distinct().take(bounded.nodeLimit)
                val existing = readNodesByIds(retained, predicate).associateBy { it.id }
                retained.mapNotNull { existing[it] }.forEach { selected[it.id] = it }
                val focusId = bounded.expandNodeId
                if (focusId != null) {
                    val focus = existing[focusId] ?: readNodesByIds(listOf(focusId), predicate).firstOrNull()
                    if (focus != null && (focus.id in selected || selected.size < bounded.nodeLimit)) {
                        selected[focus.id] = focus
                        addNeighbours(focus.id, selected, bounded, predicate)
                    }
                }
            }
            val edges = readEdges(selected.keys.toList(), bounded)
            GraphSubgraph(GraphData(selected.values.toList(), edges), count)
        }
    }

    suspend fun search(request: GraphEntitySearchRequest): List<KgNode> = withContext(Dispatchers.IO) {
        val term = KgIdentity.normalize(request.query).take(512)
        if (term.isBlank()) return@withContext emptyList()
        database.withTransaction {
            val limit = request.limit.coerceIn(1, 100)
            val predicate = activeNode(request.docId, request.conceptOnly)
            val upper = term + String(Character.toChars(Character.MAX_CODE_POINT))
            // 精确和前缀匹配使用 normalized_name 索引；包含匹配仅补齐有界结果。
            val prefix = readNodes(
                "SELECT n.* FROM kg_nodes n WHERE ${predicate.sql} AND n.normalized_name >= ? AND n.normalized_name < ? " +
                    "ORDER BY CASE WHEN n.normalized_name = ? THEN 0 ELSE 1 END, n.normalized_name, n.type, n.id LIMIT ?",
                predicate.args + listOf(term, upper, term, limit),
            )
            if (prefix.size == limit) return@withTransaction prefix
            val contained = readNodes(
                "SELECT n.* FROM kg_nodes n WHERE ${predicate.sql} AND instr(n.normalized_name, ?) > 0 " +
                    "AND NOT (n.normalized_name >= ? AND n.normalized_name < ?) ORDER BY n.normalized_name, n.type, n.id LIMIT ?",
                predicate.args + listOf(term, term, upper, limit - prefix.size),
            )
            prefix + contained
        }
    }

    private fun addNeighbours(
        focusId: String,
        selected: LinkedHashMap<String, KgNode>,
        request: GraphSubgraphRequest,
        nodePredicate: SqlPredicate,
    ) {
        val targetSize = minOf(request.nodeLimit, selected.size + GRAPH_EXPANSION_BATCH_SIZE)
        val edgePredicate = activeEvidence("edge_id", "e.id", request.docId)
        var offset = 0
        // 分页跳过已经显示的邻居，避免把数千个 ID 放进同一条 SQLite 绑定语句。
        while (selected.size < targetSize) {
            val batch = readNodes(
                "SELECT DISTINCT n.* FROM kg_edges e JOIN kg_nodes n ON n.id = CASE WHEN e.source_id = ? THEN e.target_id ELSE e.source_id END " +
                    "WHERE (e.source_id = ? OR e.target_id = ?) AND ${edgePredicate.sql} AND ${nodePredicate.sql} " +
                    "ORDER BY COALESCE(n.updated_at, n.created_at) DESC, n.id LIMIT ? OFFSET ?",
                listOf(focusId, focusId, focusId) + edgePredicate.args + nodePredicate.args + listOf(128, offset),
            )
            if (batch.isEmpty()) break
            for (node in batch) {
                if (selected.size == targetSize) break
                selected.putIfAbsent(node.id, node)
            }
            if (batch.size < 128) break
            offset += batch.size
        }
    }

    private fun readNodesByIds(ids: List<String>, predicate: SqlPredicate): List<KgNode> =
        ids.chunked(400).flatMap { chunk ->
            readNodes(
                "SELECT n.* FROM kg_nodes n WHERE n.id IN (${placeholders(chunk.size)}) AND ${predicate.sql}",
                chunk + predicate.args,
            )
        }

    private suspend fun readEdges(ids: List<String>, request: GraphSubgraphRequest): List<KgEdge> {
        if (ids.isEmpty()) return emptyList()
        val predicate = activeEvidence("edge_id", "e.id", request.docId)
        val selected = linkedMapOf<String, KgEdgeEntity>()
        val chunks = ids.sorted().chunked(400)
        // 双端分批最多使用 800 个 ID 参数，兼容 SQLite 的绑定数量限制。
        outer@ for (sources in chunks) {
            for (targets in chunks) {
                coroutineContext.ensureActive()
                query(
                    "SELECT e.* FROM kg_edges e WHERE e.source_id IN (${placeholders(sources.size)}) " +
                        "AND e.target_id IN (${placeholders(targets.size)}) AND ${predicate.sql} ORDER BY e.created_at DESC, e.id LIMIT ?",
                    sources + targets + predicate.args + (request.edgeLimit - selected.size),
                ) {
                    KgEdgeEntity(string("id"), string("source_id"), string("target_id"), string("relation"),
                        getDouble(getColumnIndexOrThrow("weight")), getLong(getColumnIndexOrThrow("created_at")), string("normalized_relation"))
                }.forEach { selected[it.id] = it }
                if (selected.size >= request.edgeLimit) break@outer
            }
        }
        val evidence = selected.keys.toList().chunked(400).flatMap { database.kgEvidenceDao().getActiveForEdges(it) }
            .filter { request.docId == null || it.docId == request.docId }.groupBy { it.edgeId }
        return selected.values.map { edge ->
            val sources = evidence[edge.id].orEmpty()
            val documents = sources.mapNotNull { it.docId }.distinct().sorted()
            KgEdge(
                id = edge.id, sourceId = edge.sourceId, targetId = edge.targetId, relation = edge.relation,
                weight = sources.mapNotNull { it.weight }.maxOrNull() ?: edge.weight,
                docId = documents.singleOrNull(),
                sourceType = if (sources.any { it.sourceType == "document" }) "full" else sources.firstOrNull()?.sourceType ?: "legacy",
                createdAt = edge.createdAt, documentIds = documents,
            )
        }
    }

    private fun activeNode(docId: String?, conceptOnly: Boolean): SqlPredicate {
        val evidence = activeEvidence("node_id", "n.id", docId)
        return SqlPredicate(evidence.sql + if (conceptOnly) " AND n.type = 'concept'" else "", evidence.args)
    }

    private fun activeEvidence(column: String, owner: String, docId: String?): SqlPredicate = SqlPredicate(
        "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.$column = $owner AND $ACTIVE_KG_EVIDENCE" +
            if (docId == null) ")" else " AND p.doc_id = ?)",
        if (docId == null) emptyList() else listOf(docId),
    )

    private fun readNodes(sql: String, args: List<Any?>): List<KgNode> = query(sql, args) {
        KgNode(id = string("id"), name = string("name"), type = string("type"), metadata = nullableString("metadata"),
            createdAt = getLong(getColumnIndexOrThrow("created_at")))
    }

    private fun <T> query(sql: String, args: List<Any?>, read: Cursor.() -> T): List<T> =
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.read()) }
        }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(",")
    private data class SqlPredicate(val sql: String, val args: List<Any?> = emptyList())
}
