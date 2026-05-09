package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",
    val model: String = "",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    @ColumnInfo(name = "avatar_path")
    val avatarPath: String? = null,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val max_tokens: Int? = null,
)
