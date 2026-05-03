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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: KgNodeEntity)

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

    @Query("DELETE FROM kg_nodes WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
