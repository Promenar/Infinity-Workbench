package com.promenar.infinity.data.local.db.entity

import androidx.room.*

/** 全量快照绑定消息；任何消息删除路径都通过外键撤销该消息的状态版本。 */
@Entity(
    tableName = "state_board_versions",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["session_id", "id"], childColumns = ["session_id", "anchor_message_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("session_id"), Index(value = ["session_id", "anchor_message_id"])],
)
data class StateBoardVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "anchor_message_id") val anchorMessageId: String,
    val source: String,
    val snapshot: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
