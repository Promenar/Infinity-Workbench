package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity

@Dao
interface KgEdgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edge: KgEdgeEntity)

    @Delete
    suspend fun delete(edge: KgEdgeEntity)

    @Query("SELECT * FROM kg_edges WHERE id = :edgeId")
    suspend fun getById(edgeId: String): KgEdgeEntity?

    @Query("SELECT * FROM kg_edges WHERE source_id = :nodeId OR target_id = :nodeId")
    suspend fun getByNodeId(nodeId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE doc_id = :docId")
    suspend fun getByDocId(docId: String): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE doc_id IN (:docIds)")
    suspend fun getByDocIds(docIds: List<String>): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE doc_id IS NOT NULL")
    suspend fun getAllDocEdges(): List<KgEdgeEntity>

    @Query("SELECT * FROM kg_edges WHERE doc_id IS NOT NULL AND doc_id NOT IN (:docIds)")
    suspend fun getEdgesNotInDocIds(docIds: List<String>): List<KgEdgeEntity>

    @Query("DELETE FROM kg_edges WHERE id = :edgeId")
    suspend fun deleteById(edgeId: String)

    @Query("DELETE FROM kg_edges WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM kg_edges WHERE session_id IS NOT NULL")
    suspend fun deleteAllSessionEdges()

    @Query("DELETE FROM kg_edges WHERE doc_id IS NOT NULL")
    suspend fun deleteAllDocEdges(): Int

    @Query("DELETE FROM kg_edges WHERE doc_id IS NOT NULL AND doc_id NOT IN (:docIds)")
    suspend fun deleteEdgesNotInDocIds(docIds: List<String>): Int

    @Query("DELETE FROM kg_edges WHERE source_id = :nodeId AND target_id = :nodeId")
    suspend fun deleteSelfLoops(nodeId: String): Int

    @Query("UPDATE kg_edges SET source_id = :targetId WHERE source_id = :sourceId")
    suspend fun updateSourceId(sourceId: String, targetId: String)

    @Query("UPDATE kg_edges SET target_id = :targetId WHERE target_id = :sourceId")
    suspend fun updateTargetId(sourceId: String, targetId: String)

    @Query("UPDATE kg_edges SET weight = :weight, created_at = :createdAt, source_type = :sourceType WHERE id = :id")
    suspend fun updateWeight(id: String, weight: Double, createdAt: Long, sourceType: String)

    @Query("SELECT * FROM kg_edges WHERE source_id = :sourceId AND target_id = :targetId AND relation = :relation AND (doc_id = :docId OR (doc_id IS NULL AND :docId IS NULL)) AND (session_id = :sessionId OR (session_id IS NULL AND :sessionId IS NULL))")
    suspend fun findEdge(sourceId: String, targetId: String, relation: String, docId: String?, sessionId: String?): KgEdgeEntity?

    @Query("SELECT e.*, n1.name as source_name, n2.name as target_name FROM kg_edges e JOIN kg_nodes n1 ON e.source_id = n1.id JOIN kg_nodes n2 ON e.target_id = n2.id WHERE (e.source_id IN (:nodeIds) OR e.target_id IN (:nodeIds)) AND (:docFilter) LIMIT :limit")
    suspend fun getEdgesWithNamesByNodeIds(nodeIds: List<String>, docFilter: String, limit: Int = 20): List<EdgeWithNames>

    data class EdgeWithNames(
        val id: String,
        val source_id: String,
        val target_id: String,
        val relation: String,
        val weight: Double,
        val doc_id: String?,
        val session_id: String?,
        val agent_id: String?,
        val source_type: String?,
        val created_at: Long,
        val source_name: String,
        val target_name: String
    )

    @Query("SELECT COUNT(*) FROM kg_edges WHERE doc_id IN (:docIds)")
    suspend fun countByDocIds(docIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM kg_edges")
    suspend fun getCount(): Int
}
