package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE is_pinned = 1 ORDER BY updated_at DESC")
    fun observePinned(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :lastMessage, time = :time, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateLastMessage(sessionId: String, lastMessage: String?, time: String?, updatedAt: Long)

    @Query("UPDATE sessions SET draft = :draft, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateDraft(sessionId: String, draft: String?, updatedAt: Long)

    @Query("UPDATE sessions SET is_pinned = :isPinned, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updatePinned(sessionId: String, isPinned: Int, updatedAt: Long)

    @Query("UPDATE sessions SET inference_params = :inferenceParams, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateInferenceParams(sessionId: String, inferenceParams: String?, updatedAt: Long)

    @Query("UPDATE sessions SET model_id = :modelId, options = :options, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateModel(sessionId: String, modelId: String?, options: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_mcp_server_ids = :activeMcpServerIds, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveMcpServerIds(sessionId: String, activeMcpServerIds: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_skill_ids = :activeSkillIds, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveSkillIds(sessionId: String, activeSkillIds: String?, updatedAt: Long)

    @Query("UPDATE sessions SET active_task = :activeTask, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveTask(sessionId: String, activeTask: String?, updatedAt: Long)

    @Query("UPDATE sessions SET options = :options, rag_options = :ragOptions, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateOptions(sessionId: String, options: String?, ragOptions: String?, updatedAt: Long)
}
