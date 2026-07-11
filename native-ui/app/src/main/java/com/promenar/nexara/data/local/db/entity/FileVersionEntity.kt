package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_versions",
    indices = [
        Index("file_uuid"),
        Index("workspace_root_uuid"),
    ],
)
data class FileVersionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "file_uuid")
    val fileUuid: String,
    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String,
    val hash: String,
    @ColumnInfo(name = "content_path")
    val contentPath: String,
    @ColumnInfo(name = "created_by_session_id")
    val createdBySessionId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
