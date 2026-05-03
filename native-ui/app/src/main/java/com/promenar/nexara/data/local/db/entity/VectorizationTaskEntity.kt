package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vectorization_tasks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("status"), Index("doc_id"), Index("session_id")]
)
data class VectorizationTaskEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val status: String,
    @ColumnInfo(name = "doc_id")
    val docId: String? = null,
    @ColumnInfo(name = "doc_title")
    val docTitle: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "user_content")
    val userContent: String? = null,
    @ColumnInfo(name = "ai_content")
    val aiContent: String? = null,
    @ColumnInfo(name = "user_message_id")
    val userMessageId: String? = null,
    @ColumnInfo(name = "assistant_message_id")
    val assistantMessageId: String? = null,
    @ColumnInfo(name = "last_chunk_index")
    val lastChunkIndex: Int = 0,
    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int? = null,
    val progress: Double = 0.0,
    val error: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
