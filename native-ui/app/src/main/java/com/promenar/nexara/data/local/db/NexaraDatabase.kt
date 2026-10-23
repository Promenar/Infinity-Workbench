package com.promenar.nexara.data.local.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.local.db.dao.AgentRuleRevisionDao
import com.promenar.nexara.data.local.db.dao.ArtifactDao
import com.promenar.nexara.data.local.db.dao.AttachmentDao
import com.promenar.nexara.data.local.db.dao.AuditLogDao
import com.promenar.nexara.data.local.db.dao.ContextSummaryDao
import com.promenar.nexara.data.local.db.dao.DocumentTagDao
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.FileVersionDao
import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgJitCacheDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.dao.KgEvidenceDao
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.ParameterSchemeDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.SessionDeletionDao
import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.dao.SshHostDao
import com.promenar.nexara.data.local.db.dao.TagDao
import com.promenar.nexara.data.local.db.dao.TaskNodeDao
import com.promenar.nexara.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.dao.WorkspaceMutationDao
import com.promenar.nexara.data.local.db.dao.WorkspaceDao
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.local.db.entity.AgentRuleRevisionEntity
import com.promenar.nexara.data.local.db.entity.ParameterSchemeEntity
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import com.promenar.nexara.data.local.db.entity.AttachmentEntity
import com.promenar.nexara.data.local.db.entity.AuditLogEntity
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.FileVersionEntity
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.KgEvidenceEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheSourceEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.SshHostEntity
import com.promenar.nexara.data.local.db.entity.TagEntity
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import com.promenar.nexara.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.VectorFtsEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceSeqEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity

@Database(
    entities = [
        AgentEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        VectorEntity::class,
        VectorFtsEntity::class,
        ContextSummaryEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        KgNodeEntity::class,
        KgEdgeEntity::class,
        KgJitCacheEntity::class,
        KgEvidenceEntity::class,
        KgJitCacheSourceEntity::class,
        VectorizationTaskEntity::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
        CustomSkillEntity::class,
        McpServerEntity::class,
        McpToolSnapshotEntity::class,
        FileEntry::class,
        WorkspaceSeqEntity::class,
        TaskNodeEntity::class,
        ToolExecutionLedgerEntity::class,
        FileVersionEntity::class,
        WorkspaceMutationEntity::class,
        ExternalMountEntity::class,
        ExternalSnapshotEntity::class,
        SshHostEntity::class,
        WorkspaceEntity::class,
        AgentRuleRevisionEntity::class,
        ParameterSchemeEntity::class,
        com.promenar.nexara.data.local.db.entity.StateBoardVersionEntity::class,
    ],
    version = 25,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NexaraDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionDeletionDao(): SessionDeletionDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun vectorDao(): VectorDao
    abstract fun contextSummaryDao(): ContextSummaryDao
    abstract fun tagDao(): TagDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun kgNodeDao(): KgNodeDao
    abstract fun kgEdgeDao(): KgEdgeDao
    abstract fun kgJitCacheDao(): KgJitCacheDao
    abstract fun kgEvidenceDao(): KgEvidenceDao
    abstract fun vectorizationTaskDao(): VectorizationTaskDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun skillDao(): SkillDao
    abstract fun fileEntryDao(): FileEntryDao
    abstract fun workspaceSeqDao(): WorkspaceSeqDao
    abstract fun taskNodeDao(): TaskNodeDao
    abstract fun toolExecutionLedgerDao(): ToolExecutionLedgerDao
    abstract fun fileVersionDao(): FileVersionDao
    abstract fun workspaceMutationDao(): WorkspaceMutationDao
    abstract fun sshHostDao(): SshHostDao
    abstract fun externalMountDao(): ExternalMountDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun agentRuleRevisionDao(): AgentRuleRevisionDao
    abstract fun stateBoardDao(): com.promenar.nexara.data.local.db.dao.StateBoardDao
    abstract fun parameterSchemeDao(): ParameterSchemeDao
}

internal const val NEXARA_SCHEMA_V21_IDENTITY_HASH = "b59d4265cdf25db536fb91226cd85ab3"
internal const val NEXARA_SCHEMA_V22_IDENTITY_HASH = "44c225495bedbde35d20c36ffbddf6a4"
internal const val NEXARA_SCHEMA_V23_IDENTITY_HASH = "7d4eb1523842672010345f378c8b7bd9"
internal const val NEXARA_SCHEMA_V25_IDENTITY_HASH = "3345ac5e27ee342bbe8512e82146c406"
internal const val NEXARA_SCHEMA_V24_IDENTITY_HASH = "25746d91c031c8c926030a9e148b20a1"

internal fun SQLiteDatabase.hasSshHostContract(): Boolean {
    val expectedColumns = mapOf(
        "id" to SshColumnContract("TEXT", true, primaryKeyPosition = 1),
        "alias" to SshColumnContract("TEXT", true),
        "hostname" to SshColumnContract("TEXT", true),
        "port" to SshColumnContract("INTEGER", true, defaultValue = "22"),
        "username" to SshColumnContract("TEXT", true),
        "auth_type" to SshColumnContract("TEXT", true, defaultValue = "'KEY'"),
        "jump_alias" to SshColumnContract("TEXT", false),
        "trusted_key_type" to SshColumnContract("TEXT", false),
        "trusted_fingerprint" to SshColumnContract("TEXT", false),
        "keep_terminal" to SshColumnContract("INTEGER", true, defaultValue = "0"),
        "local_forward_port" to SshColumnContract("INTEGER", false),
        "forward_host" to SshColumnContract("TEXT", true, defaultValue = "'127.0.0.1'"),
        "forward_port" to SshColumnContract("INTEGER", false),
    )
    val columns = rawQuery("PRAGMA table_info(ssh_hosts)", null).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(1), SshColumnContract(
                    type = cursor.getString(2), notNull = cursor.getInt(3) == 1,
                    primaryKeyPosition = cursor.getInt(5), defaultValue = cursor.getString(4),
                ))
            }
        }
    }
    if (columns != expectedColumns) return false
    return rawQuery("PRAGMA index_list(ssh_hosts)", null).use { cursor ->
        var uniqueAlias = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == "index_ssh_hosts_alias" && cursor.getInt(2) == 1 &&
                cursor.getInt(cursor.getColumnIndexOrThrow("partial")) == 0
            ) uniqueAlias = true
        }
        uniqueAlias
    } && rawQuery("PRAGMA index_info(index_ssh_hosts_alias)", null).use { cursor ->
        cursor.moveToFirst() && cursor.getString(2) == "alias" && !cursor.moveToNext()
    }
}

private data class SshColumnContract(
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int = 0,
    val defaultValue: String? = null,
)
