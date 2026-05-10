package com.promenar.nexara.data.local.db.entity

import androidx.room.Entity
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
