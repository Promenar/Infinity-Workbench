package com.promenar.nexara.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.local.db.dao.ArtifactDao
import com.promenar.nexara.data.local.db.dao.AttachmentDao
import com.promenar.nexara.data.local.db.dao.AuditLogDao
import com.promenar.nexara.data.local.db.dao.ContextSummaryDao
import com.promenar.nexara.data.local.db.dao.DocumentTagDao
import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgJitCacheDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.dao.TagDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.dao.TaskNodeDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import com.promenar.nexara.data.local.db.entity.AttachmentEntity
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.local.db.entity.AuditLogEntity
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.TagEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.VectorFtsEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceSeqEntity

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
        VectorizationTaskEntity::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
        CustomSkillEntity::class,
        McpServerEntity::class,
        FileEntry::class,
        WorkspaceSeqEntity::class,
        TaskNodeEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class NexaraDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun vectorDao(): VectorDao
    abstract fun contextSummaryDao(): ContextSummaryDao
    abstract fun tagDao(): TagDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun kgNodeDao(): KgNodeDao
    abstract fun kgEdgeDao(): KgEdgeDao
    abstract fun kgJitCacheDao(): KgJitCacheDao
    abstract fun vectorizationTaskDao(): VectorizationTaskDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun skillDao(): SkillDao
    abstract fun fileEntryDao(): FileEntryDao
    abstract fun workspaceSeqDao(): WorkspaceSeqDao
    abstract fun taskNodeDao(): TaskNodeDao

    companion object {
        private fun columnExists(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
            val cursor = db.query("PRAGMA table_info(`$tableName`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) return true
                }
            } catch (_: Exception) {
                // Ignore errors during check
            } finally {
                cursor.close()
            }
            return false
        }

        private fun safeAddColumn(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String, columnName: String, columnDef: String) {
            if (!columnExists(db, tableName, columnName)) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN $columnName $columnDef")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // agents table missing columns
                safeAddColumn(db, "agents", "temperature", "REAL")
                safeAddColumn(db, "agents", "top_p", "REAL")
                safeAddColumn(db, "agents", "max_tokens", "INTEGER")
                safeAddColumn(db, "agents", "rag_config", "TEXT")
                safeAddColumn(db, "agents", "retrieval_config", "TEXT")
                safeAddColumn(db, "agents", "use_inherited_config", "INTEGER NOT NULL DEFAULT 1")

                // sessions table missing columns
                safeAddColumn(db, "sessions", "draft", "TEXT")
                safeAddColumn(db, "sessions", "execution_mode", "TEXT NOT NULL DEFAULT 'auto'")
                safeAddColumn(db, "sessions", "loop_status", "TEXT NOT NULL DEFAULT 'idle'")
                safeAddColumn(db, "sessions", "pending_intervention", "TEXT")
                safeAddColumn(db, "sessions", "approval_request", "TEXT")
                safeAddColumn(db, "sessions", "rag_options", "TEXT")
                safeAddColumn(db, "sessions", "inference_params", "TEXT")
                safeAddColumn(db, "sessions", "active_task", "TEXT")
                safeAddColumn(db, "sessions", "stats", "TEXT")
                safeAddColumn(db, "sessions", "options", "TEXT")
                safeAddColumn(db, "sessions", "active_mcp_server_ids", "TEXT")
                safeAddColumn(db, "sessions", "active_skill_ids", "TEXT")
                safeAddColumn(db, "sessions", "workspace_path", "TEXT")

                // messages table missing columns
                safeAddColumn(db, "messages", "model_id", "TEXT")
                safeAddColumn(db, "messages", "status", "TEXT")
                safeAddColumn(db, "messages", "reasoning", "TEXT")
                safeAddColumn(db, "messages", "thought_signature", "TEXT")
                safeAddColumn(db, "messages", "images", "TEXT")
                safeAddColumn(db, "messages", "tokens", "TEXT")
                safeAddColumn(db, "messages", "citations", "TEXT")
                safeAddColumn(db, "messages", "rag_references", "TEXT")
                safeAddColumn(db, "messages", "rag_progress", "TEXT")
                safeAddColumn(db, "messages", "rag_metadata", "TEXT")
                safeAddColumn(db, "messages", "rag_references_loading", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "messages", "execution_steps", "TEXT")
                safeAddColumn(db, "messages", "tool_calls", "TEXT")
                safeAddColumn(db, "messages", "pending_approval_tool_ids", "TEXT")
                safeAddColumn(db, "messages", "tool_call_id", "TEXT")
                safeAddColumn(db, "messages", "name", "TEXT")
                safeAddColumn(db, "messages", "planning_task", "TEXT")
                safeAddColumn(db, "messages", "is_archived", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "messages", "vectorization_status", "TEXT")
                safeAddColumn(db, "messages", "layout_height", "REAL")
                safeAddColumn(db, "messages", "tool_results", "TEXT")
                safeAddColumn(db, "messages", "files", "TEXT")
                safeAddColumn(db, "messages", "is_error", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "messages", "error_message", "TEXT")

                // vectors table missing columns
                safeAddColumn(db, "vectors", "stale", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "vectors", "version", "INTEGER NOT NULL DEFAULT 1")
                safeAddColumn(db, "vectors", "file_uuid", "TEXT")

                // kg_nodes table missing columns
                safeAddColumn(db, "kg_nodes", "stale", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "kg_nodes", "file_uuid", "TEXT")

                // kg_edges table missing columns
                safeAddColumn(db, "kg_edges", "stale", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "kg_edges", "file_uuid", "TEXT")

                // Create vectorization_tasks table if missing
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `vectorization_tasks` (
                        `id` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `doc_id` TEXT, 
                        `doc_title` TEXT, 
                        `session_id` TEXT, 
                        `user_content` TEXT, 
                        `ai_content` TEXT, 
                        `user_message_id` TEXT, 
                        `assistant_message_id` TEXT, 
                        `last_chunk_index` INTEGER NOT NULL, 
                        `total_chunks` INTEGER, 
                        `progress` REAL NOT NULL, 
                        `error` TEXT, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectorization_tasks_status` ON `vectorization_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectorization_tasks_doc_id` ON `vectorization_tasks` (`doc_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectorization_tasks_session_id` ON `vectorization_tasks` (`session_id`)")

                // vectors_fts virtual table
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING fts4(content, contentEntity=`vectors`)")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_nodes (
                        id TEXT PRIMARY KEY NOT NULL,
                        session_id TEXT NOT NULL,
                        parent_id TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'pending',
                        note TEXT,
                        artifact_file_uuids TEXT,
                        is_collapsed INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_nodes_session ON task_nodes(session_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_nodes_parent ON task_nodes(parent_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_nodes_status ON task_nodes(status)")
                safeAddColumn(db, "sessions", "active_task_tree_id", "TEXT")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS documents")
                db.execSQL("DROP TABLE IF EXISTS folders")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workspace_files (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        parent_uuid TEXT,
                        name TEXT NOT NULL,
                        hash TEXT NOT NULL,
                        mime_type TEXT,
                        size_bytes INTEGER NOT NULL DEFAULT 0,
                        is_directory INTEGER NOT NULL DEFAULT 0,
                        physical_root_path TEXT NOT NULL,
                        materialized_path TEXT NOT NULL,
                        vectorized_at INTEGER,
                        vector_version INTEGER NOT NULL DEFAULT 1,
                        kg_extracted_at INTEGER,
                        kg_version INTEGER NOT NULL DEFAULT 1,
                        last_write_session_id TEXT,
                        locked_by_session_id TEXT,
                        lock_expires_at INTEGER,
                        in_recycle_bin INTEGER NOT NULL DEFAULT 0,
                        recycled_at INTEGER,
                        original_parent_uuid TEXT,
                        original_materialized_path TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wf_parent ON workspace_files(parent_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wf_materialized_path ON workspace_files(materialized_path)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wf_hash ON workspace_files(hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wf_is_directory ON workspace_files(is_directory)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wf_recycle_bin ON workspace_files(in_recycle_bin, physical_root_path, recycled_at)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workspace_seq (
                        date_key TEXT PRIMARY KEY NOT NULL,
                        last_seq INTEGER NOT NULL
                    )
                """.trimIndent())
                safeAddColumn(db, "sessions", "workspace_root_uuid", "TEXT")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "messages", "user_images", "TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `custom_skills` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `parametersSchema` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `type` TEXT NOT NULL DEFAULT 'user',
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `mcp_servers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `type` TEXT NOT NULL DEFAULT 'http',
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `callIntervalMs` INTEGER NOT NULL DEFAULT 1000,
                        `isDefault` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
    }
}
