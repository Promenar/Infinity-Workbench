package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_tags",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    primaryKeys = ["doc_id", "tag_id"],
    indices = [Index("tag_id")]
)
data class DocumentTagEntity(
    @ColumnInfo(name = "doc_id")
    val docId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
