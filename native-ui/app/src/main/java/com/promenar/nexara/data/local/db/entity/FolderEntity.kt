package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("parent_id")]
)
data class FolderEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
