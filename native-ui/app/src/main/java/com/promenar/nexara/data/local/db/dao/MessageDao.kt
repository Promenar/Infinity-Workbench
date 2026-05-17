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

    @Query("SELECT * FROM messages WHERE tokens IS NOT NULL")
    suspend fun getMessagesWithTokens(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE tokens IS NOT NULL AND session_id = :sessionId")
    suspend fun getMessagesWithTokensBySession(sessionId: String): List<MessageEntity>

    @Query("UPDATE messages SET tokens = NULL")
    suspend fun clearAllTokenData()

    // ── SQL 级聚合查询（替代 Kotlin 层全表扫描）──

    @Query("""
        SELECT COALESCE(SUM(CAST(json_extract(tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(tokens, '$.output') AS INTEGER)), 0) as total_output
        FROM messages
        WHERE tokens IS NOT NULL
    """)
    suspend fun getTotalTokenUsage(): TokenTotalRow

    @Query("""
        SELECT COALESCE(model_id, 'unknown') as model_id,
               COALESCE(SUM(CAST(json_extract(tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(tokens, '$.output') AS INTEGER)), 0) as total_output
        FROM messages
        WHERE tokens IS NOT NULL
        GROUP BY model_id
        ORDER BY total_input + total_output DESC
    """)
    suspend fun getTokenUsageByModel(): List<ModelTokenRow>

    @Query("""
        SELECT m.session_id, s.title,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.output') AS INTEGER)), 0) as total_output
        FROM messages m
        LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.tokens IS NOT NULL
        GROUP BY m.session_id
        ORDER BY total_input + total_output DESC
        LIMIT :limit
    """)
    suspend fun getSessionTokenRanking(limit: Int): List<SessionTokenRow>

    @Query("""
        SELECT date(m.created_at / 1000, 'unixepoch') as day,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.input') AS INTEGER)), 0) as total_input,
               COALESCE(SUM(CAST(json_extract(m.tokens, '$.output') AS INTEGER)), 0) as total_output
        FROM messages m
        WHERE m.tokens IS NOT NULL AND m.created_at >= :sinceTimestamp
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun getDailyTokenTrend(sinceTimestamp: Long): List<DailyTokenRow>
}

data class TokenTotalRow(
    val total_input: Long,
    val total_output: Long
)

data class ModelTokenRow(
    val model_id: String,
    val total_input: Long,
    val total_output: Long
)

data class SessionTokenRow(
    val session_id: String,
    val title: String?,
    val total_input: Long,
    val total_output: Long
)

data class DailyTokenRow(
    val day: String?,
    val total_input: Long,
    val total_output: Long
)
