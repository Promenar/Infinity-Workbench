package com.promenar.nexara.data.local.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "custom_skills")
data class CustomSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String, // JSON Schema
    val code: String,            // JS or DSL code
    val type: String = "user",   // user or meta
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val type: String = "http",    // http, stdio
    val enabled: Boolean = true,
    val callIntervalMs: Long = 1000,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "mcp_tool_snapshots",
    primaryKeys = ["server_id", "remote_tool_name"],
    foreignKeys = [
        ForeignKey(
            entity = McpServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["server_id"])],
)
data class McpToolSnapshotEntity(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "remote_tool_name") val remoteToolName: String,
    val description: String,
    @ColumnInfo(name = "input_schema_json") val inputSchemaJson: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
