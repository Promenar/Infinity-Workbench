package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity

@Dao
interface VectorizationTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: VectorizationTaskEntity)

    @Update
    suspend fun update(task: VectorizationTaskEntity)

    @Delete
    suspend fun delete(task: VectorizationTaskEntity)

    @Query("SELECT * FROM vectorization_tasks WHERE id = :taskId")
    suspend fun getById(taskId: String): VectorizationTaskEntity?

    @Query("SELECT * FROM vectorization_tasks WHERE status = :status")
    suspend fun getByStatus(status: String): List<VectorizationTaskEntity>

    @Query("SELECT * FROM vectorization_tasks WHERE doc_id = :docId")
    suspend fun getByDocId(docId: String): List<VectorizationTaskEntity>

    @Query("SELECT * FROM vectorization_tasks WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<VectorizationTaskEntity>

    @Query("UPDATE vectorization_tasks SET status = :status, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, updatedAt: Long)
}
