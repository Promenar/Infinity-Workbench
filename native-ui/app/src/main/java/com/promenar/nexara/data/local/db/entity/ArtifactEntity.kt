package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artifacts",
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
        Index("type"),
        Index("created_at"),
    ]
)
data class ArtifactEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "preview_image")
    val previewImage: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "workspace_path")
    val workspacePath: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    val tags: String? = null,
)
