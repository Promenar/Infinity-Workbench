package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.KgNodeEntity

@Dao
interface KgNodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(node: KgNodeEntity): Long

    @Update
    suspend fun update(node: KgNodeEntity)

    @Delete
    suspend fun delete(node: KgNodeEntity)

    @Query("SELECT * FROM kg_nodes WHERE id = :nodeId")
    suspend fun getById(nodeId: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE name = :name")
    suspend fun getByName(name: String): KgNodeEntity?

    @Query("SELECT * FROM kg_nodes WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE source_type = :sourceType")
    suspend fun getBySourceType(sourceType: String): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes")
    suspend fun getAll(): List<KgNodeEntity>

    @Query("SELECT * FROM kg_nodes WHERE id IN (:nodeIds)")
    suspend fun getByIds(nodeIds: List<String>): List<KgNodeEntity>

    @Query("DELETE FROM kg_nodes WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM kg_nodes WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM kg_nodes WHERE session_id IS NOT NULL")
    suspend fun deleteAllSessionNodes()

    @Query("DELETE FROM kg_nodes WHERE id NOT IN (SELECT source_id FROM kg_edges) AND id NOT IN (SELECT target_id FROM kg_edges)")
    suspend fun deleteOrphanNodes(): Int

    @Query("UPDATE kg_nodes SET type = :type, metadata = :metadata, updated_at = :updatedAt, source_type = :sourceType WHERE id = :id")
    suspend fun updateTypeMetadata(id: String, type: String, metadata: String, updatedAt: Long, sourceType: String)

    @Query("SELECT COUNT(*) FROM kg_nodes")
    suspend fun getCount(): Int
}
