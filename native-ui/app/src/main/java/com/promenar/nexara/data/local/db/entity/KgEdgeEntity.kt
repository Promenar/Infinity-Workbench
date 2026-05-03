package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kg_edges",
    foreignKeys = [
        ForeignKey(
            entity = KgNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KgNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["doc_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("source_id"), Index("target_id"), Index("doc_id")]
)
data class KgEdgeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    val relation: String,
    val weight: Double = 1.0,
    @ColumnInfo(name = "doc_id")
    val docId: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    @ColumnInfo(name = "source_type")
    val sourceType: String = "full",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
