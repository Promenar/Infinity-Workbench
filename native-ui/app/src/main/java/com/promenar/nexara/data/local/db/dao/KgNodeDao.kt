package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.KgNodeEntity

private const val ACTIVE_KG_NODE = "((file_uuid IS NOT NULL AND file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (file_uuid IS NULL AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE (e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id) AND (e.file_uuid IS NOT NULL OR e.doc_id IN (SELECT uuid FROM workspace_files)))) OR (file_uuid IS NULL AND EXISTS (SELECT 1 FROM kg_edges e WHERE (e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id) AND ((e.file_uuid IS NOT NULL AND e.file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (e.file_uuid IS NULL AND (e.doc_id IS NULL OR e.doc_id NOT IN (SELECT uuid FROM workspace_files) OR e.doc_id IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)))))))"

@Dao
interface KgNodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(node: KgNodeEntity): Long

    @Update
    suspend fun update(node: KgNodeEntity)

    @Delete
    suspend fun delete(node: KgNodeEntity)

    @Query("SELECT * FROM kg_nodes WHERE id = :nodeId AND " + ACTIVE_KG_NODE)
    suspend fun getById(nodeId: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE name = :name AND " + ACTIVE_KG_NODE)
    suspend fun getByName(name: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE session_id = :sessionId AND " + ACTIVE_KG_NODE)
    suspend fun getBySessionId(sessionId: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE source_type = :sourceType AND " + ACTIVE_KG_NODE)
    suspend fun getBySourceType(sourceType: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE " + ACTIVE_KG_NODE)
    suspend fun getAll(): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE id IN (:nodeIds) AND " + ACTIVE_KG_NODE)
    suspend fun getByIds(nodeIds: List<String>): List<KgNodeEntity>

    @Query("DELETE FROM kg_nodes WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM kg_nodes WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM kg_nodes WHERE file_uuid = :fileUuid")
    suspend fun deleteByFileUuid(fileUuid: String): Int

    @Query("DELETE FROM kg_nodes WHERE session_id IS NOT NULL")
    suspend fun deleteAllSessionNodes()

    @Query("DELETE FROM kg_nodes WHERE id NOT IN (SELECT source_id FROM kg_edges) AND id NOT IN (SELECT target_id FROM kg_edges)")
    suspend fun deleteOrphanNodes(): Int

    @Query("DELETE FROM kg_nodes WHERE id IN (:nodeIds) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.source_id = kg_nodes.id OR e.target_id = kg_nodes.id)")
    suspend fun deleteOrphansByIds(nodeIds: List<String>): Int

    @Query("UPDATE kg_nodes SET type = :type, metadata = :metadata, updated_at = :updatedAt, source_type = :sourceType WHERE id = :id")
    suspend fun updateTypeMetadata(id: String, type: String, metadata: String, updatedAt: Long, sourceType: String)

    @Query("SELECT COUNT(*) FROM kg_nodes WHERE " + ACTIVE_KG_NODE)
    suspend fun getCount(): Int

    @Query("DELETE FROM kg_nodes")
    suspend fun clearAll()
}
