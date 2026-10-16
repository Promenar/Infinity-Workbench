package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "external_snapshots",
    primaryKeys = ["mount_id", "source_path", "workspace_root_uuid"],
    foreignKeys = [
        ForeignKey(
            entity = ExternalMountEntity::class,
            parentColumns = ["id"],
            childColumns = ["mount_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mount_id")],
)
data class ExternalSnapshotEntity(
    @ColumnInfo(name = "mount_id")
    val mountId: String,
    @ColumnInfo(name = "source_path")
    val sourcePath: String,
    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String,
    @ColumnInfo(name = "file_uuid")
    val fileUuid: String,
    @ColumnInfo(name = "source_modified")
    val sourceModified: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
)
