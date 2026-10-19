package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "name_customized")
    val nameCustomized: Boolean = false,
    @ColumnInfo(name = "description_customized")
    val descriptionCustomized: Boolean = false,
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
    @ColumnInfo(name = "rag_config")
    val ragConfig: com.promenar.nexara.data.agent.AgentRagConfig? = null,
    @ColumnInfo(name = "retrieval_config")
    val retrievalConfig: com.promenar.nexara.data.agent.AgentRetrievalConfig? = null,
    @ColumnInfo(name = "use_inherited_config")
    val useInheritedConfig: Boolean = true,
    @ColumnInfo(name = "execution_mode", defaultValue = "semi")
    val executionMode: String = "semi",
    @ColumnInfo(name = "skill_ids", defaultValue = "[]")
    val skillIds: String = "[]",
    @ColumnInfo(name = "mcp_server_ids", defaultValue = "[]")
    val mcpServerIds: String = "[]",
    /** 新建会话继承的自选工作区；为空时使用会话私有工作区。 */
    @ColumnInfo(name = "default_workspace_id")
    val defaultWorkspaceId: String? = null,
    /** 只经专用单列更新写入；整行更新保留数据库中的现值。 */
    @ColumnInfo(name = "user_rules", defaultValue = "''")
    val userRules: String = "",
)
