package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("session_id"),
        Index("session_id", "created_at"),
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    val status: String? = null,
    val reasoning: String? = null,
    @ColumnInfo(name = "thought_signature")
    val thoughtSignature: String? = null,
    val images: String? = null,
    val tokens: String? = null,
    val citations: String? = null,
    @ColumnInfo(name = "rag_references")
    val ragReferences: String? = null,
    @ColumnInfo(name = "rag_progress")
    val ragProgress: String? = null,
    @ColumnInfo(name = "rag_metadata")
    val ragMetadata: String? = null,
    @ColumnInfo(name = "rag_references_loading")
    val ragReferencesLoading: Int = 0,
    @ColumnInfo(name = "execution_steps")
    val executionSteps: String? = null,
    @ColumnInfo(name = "tool_calls")
    val toolCalls: String? = null,
    @ColumnInfo(name = "pending_approval_tool_ids")
    val pendingApprovalToolIds: String? = null,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
    @ColumnInfo(name = "planning_task")
    val planningTask: String? = null,
    @ColumnInfo(name = "is_archived")
    val isArchived: Int = 0,
    @ColumnInfo(name = "vectorization_status")
    val vectorizationStatus: String? = null,
    @ColumnInfo(name = "layout_height")
    val layoutHeight: Double? = null,
    @ColumnInfo(name = "tool_results")
    val toolResults: String? = null,
    val files: String? = null,
    @ColumnInfo(name = "is_error")
    val isError: Int = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
