package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("folder_id")]
)
data class DocumentEntity(
    @PrimaryKey
    val id: String,
    val title: String? = null,
    val content: String? = null,
    val source: String? = null,
    val type: String = "text",
    @ColumnInfo(name = "folder_id")
    val folderId: String? = null,
    val vectorized: Int = 0,
    @ColumnInfo(name = "vector_count")
    val vectorCount: Int = 0,
    @ColumnInfo(name = "file_size")
    val fileSize: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long? = null,
    val metadata: String? = null,
    @ColumnInfo(name = "is_global")
    val isGlobal: Int = 0,
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,
)
