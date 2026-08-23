package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.nexara.data.local.db.entity.ToolLedgerStatus

@Dao
interface ToolExecutionLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ToolExecutionLedgerEntity): Long

    @Query(
        """SELECT * FROM tool_execution_ledger
            WHERE session_id = :sessionId
              AND assistant_message_id = :assistantMessageId
              AND tool_call_id = :toolCallId""",
    )
    suspend fun get(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
    ): ToolExecutionLedgerEntity?

    @Query(
        """SELECT * FROM tool_execution_ledger
            WHERE session_id = :sessionId
              AND assistant_message_id = :assistantMessageId
              AND tool_call_id = :toolCallId
              AND runtime_tool_id = :runtimeToolId
              AND arguments_digest = :argumentsDigest
              AND definition_digest = :definitionDigest""",
    )
    suspend fun getByIdentity(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        runtimeToolId: String,
        argumentsDigest: String,
        definitionDigest: String,
    ): ToolExecutionLedgerEntity?

    @Query(
        """UPDATE tool_execution_ledger
           SET status = 'APPROVED', updated_at = :updatedAt
           WHERE session_id = :sessionId
             AND assistant_message_id = :assistantMessageId
             AND tool_call_id = :toolCallId
             AND status = 'PENDING_APPROVAL'""",
    )
    suspend fun approve(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE tool_execution_ledger
           SET status = :terminalStatus,
               result_message_id = :resultMessageId,
               error = :error,
               updated_at = :updatedAt
           WHERE session_id = :sessionId
             AND assistant_message_id = :assistantMessageId
             AND tool_call_id = :toolCallId
             AND status IN (:allowedStatuses)""",
    )
    suspend fun decideTerminal(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        allowedStatuses: Set<ToolLedgerStatus>,
        terminalStatus: ToolLedgerStatus,
        resultMessageId: String,
        error: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE tool_execution_ledger
           SET status = 'RUNNING', updated_at = :updatedAt
           WHERE session_id = :sessionId
             AND assistant_message_id = :assistantMessageId
             AND tool_call_id = :toolCallId
             AND status = 'APPROVED'""",
    )
    suspend fun claim(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE tool_execution_ledger
           SET status = :terminalStatus,
               result_message_id = :resultMessageId,
               error = :error,
               updated_at = :updatedAt
           WHERE session_id = :sessionId
             AND assistant_message_id = :assistantMessageId
             AND tool_call_id = :toolCallId
             AND status = 'RUNNING'""",
    )
    suspend fun finish(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        terminalStatus: ToolLedgerStatus,
        resultMessageId: String?,
        error: String?,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM tool_execution_ledger WHERE status = 'RUNNING'")
    suspend fun getRunning(): List<ToolExecutionLedgerEntity>

    @Query("SELECT * FROM tool_execution_ledger WHERE requires_approval = 1 AND status IN ('PENDING_APPROVAL', 'APPROVED')")
    suspend fun getAwaitingApproval(): List<ToolExecutionLedgerEntity>

    @Query(
        """SELECT * FROM tool_execution_ledger
           WHERE session_id = :sessionId
             AND requires_approval = 1
             AND status IN ('PENDING_APPROVAL', 'APPROVED')""",
    )
    suspend fun getAwaitingApprovalForSession(sessionId: String): List<ToolExecutionLedgerEntity>

    @Query("SELECT * FROM tool_execution_ledger WHERE requires_approval = 0 AND status = 'APPROVED'")
    suspend fun getUnclaimedSafeApprovals(): List<ToolExecutionLedgerEntity>

    @Query(
        """SELECT * FROM tool_execution_ledger
           WHERE session_id = :sessionId AND assistant_message_id = :assistantMessageId""",
    )
    suspend fun getForAssistant(
        sessionId: String,
        assistantMessageId: String,
    ): List<ToolExecutionLedgerEntity>

    @Query(
        """UPDATE tool_execution_ledger
           SET status = 'FAILED', result_message_id = :resultMessageId,
               error = :error, updated_at = :updatedAt
           WHERE session_id = :sessionId
             AND assistant_message_id = :assistantMessageId
             AND tool_call_id = :toolCallId
             AND status IN ('PENDING_APPROVAL', 'APPROVED')""",
    )
    suspend fun invalidateAwaitingApproval(
        sessionId: String,
        assistantMessageId: String,
        toolCallId: String,
        resultMessageId: String?,
        error: String,
        updatedAt: Long,
    ): Int

    @Query(
        """DELETE FROM tool_execution_ledger
           WHERE NOT EXISTS (
               SELECT 1 FROM sessions WHERE sessions.id = tool_execution_ledger.session_id
           ) OR NOT EXISTS (
               SELECT 1 FROM messages
               WHERE messages.id = tool_execution_ledger.assistant_message_id
                 AND messages.session_id = tool_execution_ledger.session_id
           )""",
    )
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM tool_execution_ledger WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String): Int
}
