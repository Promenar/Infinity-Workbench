package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND created_at >= :timestamp")
    suspend fun deleteBySessionIdAndTimestampAfter(sessionId: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND created_at < :timestamp ORDER BY created_at ASC")
    suspend fun getBySessionBeforeTimestamp(sessionId: String, timestamp: Long): List<MessageEntity>

    @Query("UPDATE messages SET vectorization_status = :status, is_archived = :isArchived WHERE id = :messageId")
    suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Int?)

    @Query("UPDATE messages SET content = :content, tokens = :tokens, reasoning = :reasoning, citations = :citations, rag_references = :ragReferences, rag_metadata = :ragMetadata, thought_signature = :thoughtSignature, planning_task = :planningTask, execution_steps = :executionSteps, pending_approval_tool_ids = :pendingApprovalToolIds, tool_results = :toolResults, tool_calls = :toolCalls, is_error = :isError, error_message = :errorMessage WHERE id = :messageId")
    suspend fun updateContent(
        messageId: String,
        content: String?,
        tokens: String?,
        reasoning: String?,
        citations: String?,
        ragReferences: String?,
        ragMetadata: String?,
        thoughtSignature: String?,
        planningTask: String?,
        executionSteps: String?,
        pendingApprovalToolIds: String?,
        toolResults: String?,
        toolCalls: String?,
        isError: Int?,
        errorMessage: String?
    )
}
