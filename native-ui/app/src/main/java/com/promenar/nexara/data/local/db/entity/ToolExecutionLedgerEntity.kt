package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "tool_execution_ledger",
    primaryKeys = ["session_id", "assistant_message_id", "tool_call_id"],
)
data class ToolExecutionLedgerEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "assistant_message_id")
    val assistantMessageId: String,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    val status: String,
    @ColumnInfo(name = "result_message_id")
    val resultMessageId: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
