package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_summaries",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("session_id")]
)
data class ContextSummaryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "start_message_id")
    val startMessageId: String,
    @ColumnInfo(name = "end_message_id")
    val endMessageId: String,
    @ColumnInfo(name = "summary_content")
    val summaryContent: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "token_usage")
    val tokenUsage: Long? = null,
)
