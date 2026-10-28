package com.promenar.infinity.data.local.db.dao

import androidx.room.*
import com.promenar.infinity.data.local.db.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks WHERE (:sessionId IS NULL OR session_id = :sessionId) ORDER BY created_at DESC")
    fun observe(sessionId: String?): Flow<List<ScheduledTaskEntity>>
    @Query("SELECT * FROM scheduled_tasks ORDER BY created_at")
    suspend fun getAll(): List<ScheduledTaskEntity>
    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun get(id: String): ScheduledTaskEntity?
    @Insert suspend fun insert(task: ScheduledTaskEntity)
    @Update suspend fun update(task: ScheduledTaskEntity): Int
    @Query("DELETE FROM scheduled_tasks WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM scheduled_tasks WHERE session_id = :sessionId") suspend fun deleteForSession(sessionId: String)
    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE enabled = 1 AND (:sessionId IS NULL OR session_id = :sessionId)")
    suspend fun countEnabled(sessionId: String?): Int
    @Query("UPDATE scheduled_tasks SET claim_token = :token, run_count = run_count + 1, last_fired_at = :now, last_result = NULL, stop_reason = NULL WHERE id = :id AND enabled = 1 AND claim_token IS NULL AND next_fire_at <= :now AND (max_runs IS NULL OR run_count < max_runs) AND (expires_at IS NULL OR expires_at > :now)")
    suspend fun claim(id: String, token: String, now: Long): Int
}
