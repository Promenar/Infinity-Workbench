package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
// 按助手筛选会话用于“本助手 / 全部助手”检索范围。
@Entity(tableName = "sessions", indices = [Index("agent_id")])
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
    @ColumnInfo(name = "execution_mode", defaultValue = "semi")
    val executionMode: String = "semi",
    @ColumnInfo(name = "loop_status", defaultValue = "idle")
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
    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String? = null,
    @ColumnInfo(name = "active_task_tree_id")
    val activeTaskTreeId: String? = null,
    /** 自选工作区；为空时使用 [workspaceRootUuid] 指向的会话私有工作区。 */
    @ColumnInfo(name = "workspace_id")
    val workspaceId: String? = null,
    /** 为空的历史会话须由运行模式检查明确确定执行环境。 */
    @ColumnInfo(name = "run_mode")
    val runMode: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "parent_session_id")
    val parentSessionId: String? = null,
    @ColumnInfo(name = "branch_plan_json")
    val branchPlanJson: String? = null,
    @ColumnInfo(name = "worktree_directory")
    val worktreeDirectory: String? = null,
    @ColumnInfo(name = "worktree_branch")
    val worktreeBranch: String? = null,
    @ColumnInfo(name = "worktree_base")
    val worktreeBase: String? = null,
    @ColumnInfo(name = "parent_session_deleted", defaultValue = "0")
    val parentSessionDeleted: Boolean = false,
    @ColumnInfo(name = "session_auto_wake_count", defaultValue = "0")
    val sessionAutoWakeCount: Int = 0,
    @ColumnInfo(name = "session_auto_processing_paused", defaultValue = "0")
    val sessionAutoProcessingPaused: Boolean = false,
)
