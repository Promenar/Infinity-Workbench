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
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FileEntry::class,
            parentColumns = ["workspace_root_uuid", "uuid"],
            childColumns = ["workspace_root_uuid", "doc_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("status"),
        Index("doc_id"),
        Index("session_id"),
        Index(value = ["workspace_root_uuid", "doc_id"]),
        Index(value = ["workspace_root_uuid", "doc_id", "type"], unique = true),
    ]
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
    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String? = null,
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
    @ColumnInfo(name = "kg_strategy")
    val kgStrategy: String? = null,
    @ColumnInfo(name = "skip_vectorization")
    val skipVectorization: Boolean = false,
    @ColumnInfo(name = "sub_status")
    val subStatus: String? = null,
    @ColumnInfo(name = "source_mime_type")
    val sourceMimeType: String? = null,
    @ColumnInfo(name = "content_truncated")
    val contentTruncated: Boolean = false,
    @ColumnInfo(name = "target_content_hash")
    val targetContentHash: String? = null,
    @ColumnInfo(name = "target_epoch", defaultValue = "0")
    val targetEpoch: Long = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
