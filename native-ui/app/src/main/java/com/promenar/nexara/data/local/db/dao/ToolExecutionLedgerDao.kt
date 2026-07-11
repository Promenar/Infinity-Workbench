package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.ToolExecutionLedgerEntity

@Dao
interface ToolExecutionLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ToolExecutionLedgerEntity)

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

    @Update
    suspend fun update(entry: ToolExecutionLedgerEntity): Int
}
