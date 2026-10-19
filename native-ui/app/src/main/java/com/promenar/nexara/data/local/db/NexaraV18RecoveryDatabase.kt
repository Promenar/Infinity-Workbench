package com.promenar.nexara.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import com.promenar.nexara.data.local.db.entity.AttachmentEntity
import com.promenar.nexara.data.local.db.entity.AuditLogEntity
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity
import com.promenar.nexara.data.local.db.entity.FileVersionEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.TagEntity
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import com.promenar.nexara.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.VectorFtsEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceSeqEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity

/** 双库恢复候选固定使用 v18 合同，发布后由正式主库执行后续前进迁移。 */
@Database(
    entities = [
        RecoveryV18AgentEntity::class,
        RecoveryV18SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        VectorEntity::class,
        VectorFtsEntity::class,
        ContextSummaryEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        RecoveryV18KgNodeEntity::class,
        RecoveryV18KgEdgeEntity::class,
        KgJitCacheEntity::class,
        VectorizationTaskEntity::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
        CustomSkillEntity::class,
        McpServerEntity::class,
        McpToolSnapshotEntity::class,
        RecoveryV18FileEntry::class,
        WorkspaceSeqEntity::class,
        TaskNodeEntity::class,
        ToolExecutionLedgerEntity::class,
        FileVersionEntity::class,
        WorkspaceMutationEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class NexaraV18RecoveryDatabase : RoomDatabase()
