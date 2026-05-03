package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("message_id")]
)
data class AttachmentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    val type: String,
    val uri: String,
    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,
)
