package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskNodeDao {
    @Query("SELECT * FROM task_nodes WHERE session_id = :sessionId AND status != 'dropped' ORDER BY sort_order")
    fun observeActiveTree(sessionId: String): Flow<List<TaskNodeEntity>>

    @Query("SELECT * FROM task_nodes WHERE id = :id")
    suspend fun getById(id: String): TaskNodeEntity?

    @Query("SELECT * FROM task_nodes WHERE session_id = :sessionId AND status = 'doing' AND id NOT IN (SELECT DISTINCT parent_id FROM task_nodes WHERE parent_id IS NOT NULL) LIMIT 1")
    suspend fun getCurrentDoingLeaf(sessionId: String): TaskNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: TaskNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<TaskNodeEntity>)

    @Query("UPDATE task_nodes SET status = 'dropped', updated_at = :now WHERE id = :id")
    suspend fun markDropped(id: String, now: Long)

    @Query("UPDATE task_nodes SET status = 'dropped', updated_at = :now WHERE parent_id = :parentId")
    suspend fun markChildrenDropped(parentId: String, now: Long)

    @Query("DELETE FROM task_nodes WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT * FROM task_nodes WHERE session_id = :sessionId AND status != 'dropped' ORDER BY sort_order")
    suspend fun getAllActiveBySession(sessionId: String): List<TaskNodeEntity>

    @Query("UPDATE task_nodes SET status = :status, note = :note, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, note: String?, updatedAt: Long)

    @Query("UPDATE task_nodes SET status = 'todo', updated_at = :updatedAt WHERE session_id = :sessionId AND status = 'doing'")
    suspend fun resetDoingToTodo(sessionId: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM task_nodes WHERE parent_id = :parentId")
    suspend fun getChildCount(parentId: String): Int
}
