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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: VectorizationTaskEntity): Long

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

    @Query("SELECT * FROM vectorization_tasks WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id = :docId AND type = :type LIMIT 1")
    suspend fun getByWorkspaceFile(workspaceRootUuid: String, docId: String, type: String): VectorizationTaskEntity?

    @Query("SELECT COUNT(*) FROM vectorization_tasks WHERE workspace_root_uuid = :workspaceRootUuid AND doc_id = :docId AND status NOT IN ('completed')")
    suspend fun countActiveForFile(workspaceRootUuid: String, docId: String): Int

    @Query("SELECT * FROM vectorization_tasks WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<VectorizationTaskEntity>

    @Query("UPDATE vectorization_tasks SET status = :status, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM vectorization_tasks WHERE status IN ('pending', 'interrupted') ORDER BY created_at ASC")
    suspend fun getRecoverableTasks(): List<VectorizationTaskEntity>

    @Query("SELECT * FROM vectorization_tasks WHERE type = 'document_reference' AND status IN ('failed', 'partial') ORDER BY updated_at DESC")
    suspend fun getAttentionTasks(): List<VectorizationTaskEntity>

    @Query("""
        UPDATE vectorization_tasks
        SET status = 'interrupted'
        WHERE status IN ('processing', 'extracting_source', 'chunking', 'vectorizing', 'saving', 'extracting')
          AND updated_at < :staleThreshold
    """)
    suspend fun markStaleAsInterrupted(staleThreshold: Long)

    @Query("DELETE FROM vectorization_tasks WHERE status = 'completed' OR (status = 'failed' AND type != 'document_reference')")
    suspend fun deleteCompletedTasks()
}
