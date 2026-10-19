package com.promenar.nexara.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** v18 恢复合同的冻结实体；当前业务实体的前进迁移不得改变恢复候选身份。 */
@Entity(
    tableName = "workspace_files",
    indices = [
        Index("parent_uuid"),
        Index("materialized_path"),
        Index("hash"),
        Index("is_directory"),
        Index("in_recycle_bin", "physical_root_path", "recycled_at"),
        Index(value = ["workspace_root_uuid", "uuid"], unique = true),
    ]
)
data class RecoveryV18FileEntry(
    @PrimaryKey
    val uuid: String,

    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String = "",

    @ColumnInfo(name = "parent_uuid")
    val parentUuid: String?,

    val name: String,

    val hash: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean = false,

    @ColumnInfo(name = "physical_root_path")
    val physicalRootPath: String,

    @ColumnInfo(name = "materialized_path")
    val materializedPath: String,

    @ColumnInfo(name = "vectorized_at")
    val vectorizedAt: Long? = null,

    @ColumnInfo(name = "vector_version")
    val vectorVersion: Int = 1,

    @ColumnInfo(name = "kg_extracted_at")
    val kgExtractedAt: Long? = null,

    @ColumnInfo(name = "kg_version")
    val kgVersion: Int = 1,

    @ColumnInfo(name = "last_write_session_id")
    val lastWriteSessionId: String? = null,

    @ColumnInfo(name = "locked_by_session_id")
    val lockedBySessionId: String? = null,

    @ColumnInfo(name = "lock_expires_at")
    val lockExpiresAt: Long? = null,

    @ColumnInfo(name = "in_recycle_bin")
    val inRecycleBin: Boolean = false,

    @ColumnInfo(name = "recycled_at")
    val recycledAt: Long? = null,

    @ColumnInfo(name = "original_parent_uuid")
    val originalParentUuid: String? = null,

    @ColumnInfo(name = "original_materialized_path")
    val originalMaterializedPath: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "kg_nodes")
data class RecoveryV18KgNodeEntity(
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
    @ColumnInfo(name = "stale", defaultValue = "0")
    val stale: Boolean = false,
    @ColumnInfo(name = "file_uuid")
    val fileUuid: String? = null,
)

@Entity(
    tableName = "kg_edges",
    foreignKeys = [
        ForeignKey(
            entity = RecoveryV18KgNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecoveryV18KgNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("source_id"), Index("target_id"), Index("doc_id")]
)
data class RecoveryV18KgEdgeEntity(
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
    @ColumnInfo(name = "stale", defaultValue = "0")
    val stale: Boolean = false,
    @ColumnInfo(name = "file_uuid")
    val fileUuid: String? = null,
)

@Entity(tableName = "agents")
data class RecoveryV18AgentEntity(
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
)

@Entity(tableName = "sessions")
data class RecoveryV18SessionEntity(
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
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
