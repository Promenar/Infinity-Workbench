package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workspace_files",
    indices = [
        Index("parent_uuid"),
        Index("materialized_path"),
        Index("hash"),
        Index("is_directory"),
        Index("in_recycle_bin", "physical_root_path", "recycled_at"),
    ]
)
data class FileEntry(
    @PrimaryKey
    val uuid: String,

    @ColumnInfo(name = "parent_uuid")
    val parentUuid: String?,

    val name: String,

    val hash: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean = false,

    @ColumnInfo(name = "physical_root_path")
    val physicalRootPath: String,

    @ColumnInfo(name = "materialized_path")
    val materializedPath: String,

    @ColumnInfo(name = "vectorized_at")
    val vectorizedAt: Long? = null,

    @ColumnInfo(name = "vector_version")
    val vectorVersion: Int = 1,

    @ColumnInfo(name = "kg_extracted_at")
    val kgExtractedAt: Long? = null,

    @ColumnInfo(name = "kg_version")
    val kgVersion: Int = 1,

    @ColumnInfo(name = "last_write_session_id")
    val lastWriteSessionId: String? = null,

    @ColumnInfo(name = "locked_by_session_id")
    val lockedBySessionId: String? = null,

    @ColumnInfo(name = "lock_expires_at")
    val lockExpiresAt: Long? = null,

    @ColumnInfo(name = "in_recycle_bin")
    val inRecycleBin: Boolean = false,

    @ColumnInfo(name = "recycled_at")
    val recycledAt: Long? = null,

    @ColumnInfo(name = "original_parent_uuid")
    val originalParentUuid: String? = null,

    @ColumnInfo(name = "original_materialized_path")
    val originalMaterializedPath: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
