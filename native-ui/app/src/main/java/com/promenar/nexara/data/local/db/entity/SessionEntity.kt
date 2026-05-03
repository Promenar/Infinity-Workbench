package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "agent_id")
    val agentId: String,
    val title: String = "New Chat",
    @ColumnInfo(name = "last_message")
    val lastMessage: String? = null,
    val time: String? = null,
    val unread: Int = 0,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "custom_prompt")
    val customPrompt: String? = null,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Int = 0,
    @ColumnInfo(name = "scroll_offset")
    val scrollOffset: Double? = null,
    val draft: String? = null,
    @ColumnInfo(name = "execution_mode")
    val executionMode: String = "auto",
    @ColumnInfo(name = "loop_status")
    val loopStatus: String = "idle",
    @ColumnInfo(name = "pending_intervention")
    val pendingIntervention: String? = null,
    @ColumnInfo(name = "approval_request")
    val approvalRequest: String? = null,
    @ColumnInfo(name = "rag_options")
    val ragOptions: String? = null,
    @ColumnInfo(name = "inference_params")
    val inferenceParams: String? = null,
    @ColumnInfo(name = "active_task")
    val activeTask: String? = null,
    val stats: String? = null,
    val options: String? = null,
    @ColumnInfo(name = "active_mcp_server_ids")
    val activeMcpServerIds: String? = null,
    @ColumnInfo(name = "active_skill_ids")
    val activeSkillIds: String? = null,
    @ColumnInfo(name = "workspace_path")
    val workspacePath: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
