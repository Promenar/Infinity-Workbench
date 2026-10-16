package com.promenar.nexara.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.nexara.data.local.db.KgIdentity
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.ACTIVE_KG_EVIDENCE
import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KgRetrievalScope(
    val sessionId: String,
    val documentIds: List<String> = emptyList(),
    val folderIds: List<String> = emptyList(),
    val globalKnowledge: Boolean = false,
)

data class PersistentGraphResult(val seedCount: Int, val paths: List<KgPath>)

/** 从来源索引定位实体，再通过主键读取；共享实体本身不授予来源访问权。 */
class GraphRetrievalStore(private val database: NexaraDatabase) {
    suspend fun retrieve(
        query: String,
        scope: KgRetrievalScope,
        ragDocumentIds: List<String> = emptyList(),
        config: RagConfiguration = RagConfiguration(),
    ): PersistentGraphResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val (seedSql, seedArgs) = seedQuery(query, scope, ragDocumentIds, config.kgMaxSeeds.coerceIn(1, 32))
            val seeds = database.query(SimpleSQLiteQuery(seedSql, seedArgs.toTypedArray())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            if (seeds.isEmpty()) return@withTransaction PersistentGraphResult(0, emptyList())
            val nodeLimit = config.kgMaxNodes.coerceIn(2, 256)
            val edgeLimit = config.kgMaxEdges.coerceIn(1, 32)
            val acceptedNodes = seeds.take(nodeLimit).toMutableSet()
            var frontier = acceptedNodes.toList()
            val paths = mutableListOf<KgPath>()
            val seenEdges = hashSetOf<String>()
            for (distance in 1..config.kgRetrievalHops.coerceIn(1, 2)) {
                if (frontier.isEmpty() || paths.size == edgeLimit) break
                val (cte, args) = scopeQuery(scope)
                val ids = placeholders(frontier.size)
                val sql = "$cte SELECT e.id,e.source_id,e.target_id,e.relation,MAX(COALESCE(p.weight,0))," +
                    "COUNT(p.id)," +
                    "(0.35*COUNT(p.id)/(COUNT(p.id)+1.0)+0.65*MAX(COALESCE(p.weight,0))) AS score " +
                    "FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id " +
                    "WHERE e.id IN (SELECT id FROM kg_edges WHERE source_id IN ($ids) UNION SELECT id FROM kg_edges WHERE target_id IN ($ids)) " +
                    "AND ${visibleEvidence()} GROUP BY e.id ORDER BY score DESC,e.id LIMIT ?"
                val next = linkedSetOf<String>()
                database.query(SimpleSQLiteQuery(sql, (args + frontier + frontier + listOf(edgeLimit * 8)).toTypedArray())).use { cursor ->
                    while (cursor.moveToNext() && paths.size < edgeLimit) {
                        val id = cursor.getString(0)
                        if (!seenEdges.add(id)) continue
                        val source = cursor.getString(1)
                        val target = cursor.getString(2)
                        val added = listOf(source, target).filterNot { it in acceptedNodes }.distinct()
                        if (acceptedNodes.size + added.size > nodeLimit) continue
                        val nodes = database.kgNodeDao().getByIds(listOf(source, target)).map {
                            // metadata 聚合跨来源，不能作为当前可见来源的描述泄漏出去。
                            KgNode(it.id, it.name, it.type)
                        }
                        if (nodes.map { it.id }.toSet().containsAll(listOf(source, target))) {
                            val documents = database.query(SimpleSQLiteQuery(
                                "$cte SELECT DISTINCT p.doc_id FROM kg_evidence p WHERE p.edge_id=? AND p.doc_id IS NOT NULL AND ${visibleEvidence()} ORDER BY p.doc_id",
                                (args + id).toTypedArray(),
                            )).use { sources -> buildList { while (sources.moveToNext()) add(sources.getString(0)) } }
                            paths += KgPath(queryKeywords = listOf(query.take(512)), nodes = nodes,
                                edges = listOf(KgEdge(source, target, cursor.getString(3), cursor.getDouble(4))),
                                sourceDocumentIds = documents,
                                origins = listOf("persistent"), score = cursor.getDouble(6) / distance,
                                evidenceCount = cursor.getInt(5), distance = distance)
                            acceptedNodes += added
                            next += added
                        }
                    }
                }
                frontier = next.toList()
            }
            PersistentGraphResult(seeds.size, paths)
        }
    }

    internal suspend fun explainSeedQuery(query: String, scope: KgRetrievalScope): List<String> = withContext(Dispatchers.IO) {
        val (sql, args) = seedQuery(query, scope, emptyList(), 12)
        database.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
    }

    suspend fun visibleDocumentIds(scope: KgRetrievalScope): Set<String> = withContext(Dispatchers.IO) {
        val (cte, args) = scopeQuery(scope)
        database.query(SimpleSQLiteQuery("$cte SELECT id FROM visible_docs", args.toTypedArray())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun seedQuery(query: String, scope: KgRetrievalScope, ragDocumentIds: List<String>, limit: Int): Pair<String, List<Any>> {
        val normalized = KgIdentity.normalize(query).take(512)
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.filter { it.length >= 2 }.distinct().take(24).toList()
        val matches = mutableListOf("(n.normalized_name=? AND ?!='')", "(instr(n.normalized_name,?)>0 AND ?!='')", "(instr(?,n.normalized_name)>0 AND n.normalized_name!='')")
        val matchArgs = mutableListOf<Any>(normalized, normalized, normalized, normalized, normalized)
        tokens.forEach { token -> matches += "instr(n.normalized_name,?)>0"; matchArgs += token }
        val ragIds = ragDocumentIds.distinct().take(64)
        val ragMatch = "n.id IN (SELECT p.node_id FROM kg_evidence p WHERE p.doc_id IN (${placeholders(ragIds.size)}) AND ${visibleEvidence()})"
        val (cte, args) = scopeQuery(scope)
        val sql = "$cte SELECT n.id FROM kg_nodes n WHERE n.id IN (SELECT p.node_id FROM kg_evidence p " +
            "WHERE p.node_id IS NOT NULL AND ${visibleEvidence()}) AND (${matches.joinToString(" OR ")} OR $ragMatch) " +
            "ORDER BY (n.normalized_name=?) DESC, instr(?,n.normalized_name)>0 DESC,n.normalized_name,n.type LIMIT ?"
        return sql to (args + matchArgs + ragIds + listOf(normalized, normalized, limit))
    }

    private fun scopeQuery(scope: KgRetrievalScope): Pair<String, List<Any>> {
        val docs = scope.documentIds.distinct()
        val folders = scope.folderIds.distinct()
        // 标识符仅转换为十六进制字节常量，不把原始文本作为 SQL 语法，也不消耗绑定参数。
        // 完整来源保留在同一个 CTE 中，联合 JIT 依赖不因分批而被误判不可见。
        val values = if (docs.isEmpty()) "SELECT NULL WHERE 0" else "VALUES " + docs.joinToString { "(${sqlText(it)})" }
        val folderValues = if (folders.isEmpty()) "NULL" else folders.joinToString { sqlText(it) }
        val sql = "WITH RECURSIVE ctx(session_id) AS (VALUES (?)), explicit_docs(id) AS ($values), " +
            "folder_tree(id,root) AS (SELECT uuid,workspace_root_uuid FROM workspace_files WHERE uuid IN ($folderValues) AND in_recycle_bin=0 " +
            "UNION SELECT w.uuid,w.workspace_root_uuid FROM workspace_files w JOIN folder_tree f ON w.parent_uuid=f.id AND w.workspace_root_uuid=f.root WHERE w.in_recycle_bin=0), " +
            "visible_docs(id) AS (SELECT id FROM explicit_docs UNION SELECT id FROM folder_tree UNION " +
            "SELECT w.uuid FROM workspace_files w WHERE w.in_recycle_bin=0 AND w.is_directory=0 AND w.workspace_root_uuid IN " +
            "(SELECT workspace_root_uuid FROM sessions WHERE id=(SELECT session_id FROM ctx)" +
            (if (scope.globalKnowledge) " OR agent_id='__system__'" else "") + ")) "
        return sql to listOf(scope.sessionId)
    }

    private fun visibleEvidence(): String = "($ACTIVE_KG_EVIDENCE) AND " +
        "((p.session_id=(SELECT session_id FROM ctx) AND p.doc_id IS NULL) OR " +
        "(p.doc_id IN (SELECT id FROM visible_docs) AND (p.source_type IN ('document','legacy') OR p.session_id=(SELECT session_id FROM ctx)))) " +
        "AND (p.source_type!='jit' OR NOT EXISTS (SELECT 1 FROM kg_evidence d WHERE d.source_type='jit' AND d.task_id=p.task_id AND d.doc_id IS NOT NULL AND d.doc_id NOT IN (SELECT id FROM visible_docs)))"

    private fun placeholders(size: Int) = if (size == 0) "NULL" else List(size) { "?" }.joinToString()

    private fun sqlText(value: String): String = buildString {
        val hex = "0123456789abcdef"
        append("CAST(X'")
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 255
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 15])
        }
        append("' AS TEXT)")
    }
}
