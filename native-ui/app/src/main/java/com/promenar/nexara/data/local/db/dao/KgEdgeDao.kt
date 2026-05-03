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

    @Query("DELETE FROM kg_edges WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
