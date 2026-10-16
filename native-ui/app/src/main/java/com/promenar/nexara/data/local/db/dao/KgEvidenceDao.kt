package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.promenar.nexara.data.local.db.entity.KgEvidenceEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheSourceEntity

// 已知工作区文档必须存活且版本相符；legacy 没有版本时按来源存活状态读取。
internal const val ACTIVE_KG_EVIDENCE = "((p.workspace_document = 0 OR EXISTS (SELECT 1 FROM workspace_files w WHERE w.uuid = p.doc_id AND w.in_recycle_bin = 0 AND (p.content_hash = '' OR p.content_hash = w.hash))) AND (p.source_type != 'jit' OR NOT EXISTS (SELECT 1 FROM kg_evidence dependency WHERE dependency.source_type = 'jit' AND dependency.task_id = p.task_id AND dependency.workspace_document = 1 AND NOT EXISTS (SELECT 1 FROM workspace_files input WHERE input.uuid = dependency.doc_id AND input.in_recycle_bin = 0 AND (dependency.content_hash = '' OR input.hash = dependency.content_hash)))))"

@Dao
interface KgEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(evidence: KgEvidenceEntity)

    @Query("UPDATE kg_evidence SET weight = MAX(COALESCE(weight, 0), :weight) WHERE id = :id")
    suspend fun increaseWeight(id: String, weight: Double)

    @Query("UPDATE kg_evidence SET weight = :weight WHERE edge_id = :edgeId")
    suspend fun setEdgeWeights(edgeId: String, weight: Double)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCacheSources(sources: List<KgJitCacheSourceEntity>)

    @Query("SELECT * FROM kg_evidence WHERE edge_id IN (:edgeIds)")
    suspend fun getForEdges(edgeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT p.* FROM kg_evidence p WHERE p.edge_id IN (:edgeIds) AND " + ACTIVE_KG_EVIDENCE)
    suspend fun getActiveForEdges(edgeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE node_id = :nodeId")
    suspend fun getForNode(nodeId: String): List<KgEvidenceEntity>

    @Query("DELETE FROM kg_jit_cache WHERE cache_key IN (SELECT cache_key FROM kg_jit_cache_sources WHERE doc_id = :docId)")
    suspend fun invalidateDocumentCache(docId: String): Int

    // 多片段联合推断无法证明单个片段独立支持关系，任一输入删除时保守撤销整次 JIT 推断。
    @Query("DELETE FROM kg_evidence WHERE doc_id = :docId OR (source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND doc_id = :docId))")
    suspend fun deleteDocumentEvidence(docId: String): Int

    @Query("DELETE FROM kg_evidence WHERE session_id = :sessionId")
    suspend fun deleteSessionEvidence(sessionId: String): Int

    @Query("DELETE FROM kg_evidence WHERE session_id IS NOT NULL AND session_id NOT IN (:activeIds)")
    suspend fun deleteOrphanSessionEvidence(activeIds: List<String>): Int

    @Query("DELETE FROM kg_evidence WHERE doc_id IS NOT NULL AND doc_id NOT IN (:activeIds)")
    suspend fun deleteOrphanDocumentEvidence(activeIds: List<String>): Int

    @Query("DELETE FROM kg_edges WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id)")
    suspend fun collectEdges(): Int

    @Query("DELETE FROM kg_nodes WHERE NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id)")
    suspend fun collectNodes(): Int

    @Query("SELECT * FROM kg_evidence WHERE doc_id = :docId OR (source_type = 'jit' AND task_id IN (SELECT task_id FROM kg_evidence WHERE source_type = 'jit' AND doc_id = :docId))")
    suspend fun affectedByDocument(docId: String): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE session_id = :sessionId")
    suspend fun affectedBySession(sessionId: String): List<KgEvidenceEntity>

    @Query("SELECT * FROM kg_evidence WHERE session_id IS NOT NULL AND session_id NOT IN (:activeIds)")
    suspend fun orphanSessionEvidence(activeIds: List<String>): List<KgEvidenceEntity>

    @Query("SELECT DISTINCT doc_id FROM kg_evidence WHERE workspace_document = 1 AND doc_id NOT IN (:activeIds)")
    suspend fun orphanWorkspaceDocumentIds(activeIds: List<String>): List<String>

    @Query("DELETE FROM kg_edges WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = kg_edges.id)")
    suspend fun collectEdgesByIds(ids: List<String>): Int

    @Query("DELETE FROM kg_nodes WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = kg_nodes.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id)")
    suspend fun collectNodesByIds(ids: List<String>): Int

    suspend fun collectAffected(rows: List<KgEvidenceEntity>): Pair<Int, Int> {
        val edges = rows.mapNotNull { it.edgeId }.distinct().chunked(400).sumOf { collectEdgesByIds(it) }
        val nodes = rows.mapNotNull { it.nodeId }.distinct().chunked(400).sumOf { collectNodesByIds(it) }
        return edges to nodes
    }

    @Transaction
    suspend fun clearDocument(docId: String): Pair<Int, Int> {
        val affected = affectedByDocument(docId)
        invalidateDocumentCache(docId)
        deleteDocumentEvidence(docId)
        return collectAffected(affected)
    }

    @Transaction
    suspend fun clearSession(sessionId: String) {
        val affected = affectedBySession(sessionId)
        deleteSessionEvidence(sessionId)
        collectAffected(affected)
    }

    @Transaction
    suspend fun pruneSessions(activeIds: List<String>) {
        val affected = orphanSessionEvidence(activeIds)
        deleteOrphanSessionEvidence(activeIds)
        collectAffected(affected)
    }

    @Transaction
    suspend fun pruneDocuments(activeIds: List<String>): Pair<Int, Int> {
        var edges = 0
        var nodes = 0
        for (id in orphanWorkspaceDocumentIds(activeIds)) {
            val count = clearDocument(id)
            edges += count.first
            nodes += count.second
        }
        return edges to nodes
    }

    @Query("DELETE FROM kg_evidence WHERE edge_id = :edgeId")
    suspend fun deleteForEdge(edgeId: String)

    @Query("DELETE FROM kg_evidence WHERE node_id = :nodeId")
    suspend fun deleteForNode(nodeId: String)
}
