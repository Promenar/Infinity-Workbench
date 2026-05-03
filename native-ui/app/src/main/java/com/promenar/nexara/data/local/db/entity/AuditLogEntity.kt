package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_logs",
    indices = [
        Index("session_id"),
        Index("created_at"),
        Index("action"),
    ]
)
data class AuditLogEntity(
    @PrimaryKey
    val id: String,
    val action: String,
    @ColumnInfo(name = "resource_type")
    val resourceType: String,
    @ColumnInfo(name = "resource_path")
    val resourcePath: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    @ColumnInfo(name = "skill_id")
    val skillId: String? = null,
    val status: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    val metadata: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
