package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kg_nodes")
data class KgNodeEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String = "concept",
    val metadata: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    @ColumnInfo(name = "source_type")
    val sourceType: String = "full",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long? = null,
)
