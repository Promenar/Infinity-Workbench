package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tool_execution_ledger",
    primaryKeys = ["session_id", "assistant_message_id", "tool_call_id"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["session_id", "id"],
            childColumns = ["session_id", "assistant_message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "assistant_message_id"]),
    ],
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
    @ColumnInfo(name = "runtime_tool_id", defaultValue = "''")
    val runtimeToolId: String = toolName,
    @ColumnInfo(name = "arguments_digest", defaultValue = "''")
    val argumentsDigest: String = "",
    @ColumnInfo(name = "definition_digest", defaultValue = "''")
    val definitionDigest: String = "",
    @ColumnInfo(name = "requires_approval")
    val requiresApproval: Boolean,
    val status: ToolLedgerStatus,
    @ColumnInfo(name = "result_message_id")
    val resultMessageId: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

enum class ToolLedgerStatus {
    PENDING_APPROVAL,
    APPROVED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    CANCELLED,
    TIMED_OUT,
}
