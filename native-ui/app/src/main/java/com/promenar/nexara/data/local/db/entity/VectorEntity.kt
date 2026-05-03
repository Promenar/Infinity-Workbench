package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vectors",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("doc_id"), Index("session_id")]
)
data class VectorEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "doc_id")
    val docId: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    val content: String,
    val embedding: ByteArray,
    val metadata: String? = null,
    @ColumnInfo(name = "start_message_id")
    val startMessageId: String? = null,
    @ColumnInfo(name = "end_message_id")
    val endMessageId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorEntity
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
