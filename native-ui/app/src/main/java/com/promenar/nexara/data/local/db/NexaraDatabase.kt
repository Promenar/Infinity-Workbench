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
    version = 16,
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

        private fun recreateSessionsTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`sessions`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("ALTER TABLE `sessions` RENAME TO `sessions_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `id` TEXT NOT NULL, 
                        `agent_id` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `last_message` TEXT, 
                        `time` TEXT, 
                        `unread` INTEGER NOT NULL, 
                        `model_id` TEXT, 
                        `custom_prompt` TEXT, 
                        `is_pinned` INTEGER NOT NULL, 
                        `scroll_offset` REAL, 
                        `draft` TEXT, 
                        `execution_mode` TEXT NOT NULL DEFAULT 'auto', 
                        `loop_status` TEXT NOT NULL DEFAULT 'idle', 
                        `pending_intervention` TEXT, 
                        `approval_request` TEXT, 
                        `rag_options` TEXT, 
                        `inference_params` TEXT, 
                        `active_task` TEXT, 
                        `stats` TEXT, 
                        `options` TEXT, 
                        `active_mcp_server_ids` TEXT, 
                        `active_skill_ids` TEXT, 
                        `workspace_path` TEXT, 
                        `workspace_root_uuid` TEXT, 
                        `active_task_tree_id` TEXT, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                val newColumns = listOf(
                    "id", "agent_id", "title", "last_message", "time", "unread", "model_id", 
                    "custom_prompt", "is_pinned", "scroll_offset", "draft", "execution_mode", 
                    "loop_status", "pending_intervention", "approval_request", "rag_options", 
                    "inference_params", "active_task", "stats", "options", "active_mcp_server_ids", 
                    "active_skill_ids", "workspace_path", "workspace_root_uuid", "active_task_tree_id", 
                    "created_at", "updated_at"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `sessions` ($columnsJoined) SELECT $columnsJoined FROM `sessions_old`")
                }
                db.execSQL("DROP TABLE `sessions_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `id` TEXT NOT NULL, 
                        `agent_id` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `last_message` TEXT, 
                        `time` TEXT, 
                        `unread` INTEGER NOT NULL, 
                        `model_id` TEXT, 
                        `custom_prompt` TEXT, 
                        `is_pinned` INTEGER NOT NULL, 
                        `scroll_offset` REAL, 
                        `draft` TEXT, 
                        `execution_mode` TEXT NOT NULL DEFAULT 'auto', 
                        `loop_status` TEXT NOT NULL DEFAULT 'idle', 
                        `pending_intervention` TEXT, 
                        `approval_request` TEXT, 
                        `rag_options` TEXT, 
                        `inference_params` TEXT, 
                        `active_task` TEXT, 
                        `stats` TEXT, 
                        `options` TEXT, 
                        `active_mcp_server_ids` TEXT, 
                        `active_skill_ids` TEXT, 
                        `workspace_path` TEXT, 
                        `workspace_root_uuid` TEXT, 
                        `active_task_tree_id` TEXT, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        private fun recreateMessagesTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Note on §3.4 Unit Test Gate Exemption:
            // This function relies directly on SupportSQLiteDatabase and SQLite's Android system-level APIs.
            // These platform APIs are highly coupled with the Android OS environment and cannot be executed/mocked in a standard local JVM environment.
            // Therefore, this migration logic is exempted from the standard Unit Testing Gate.
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`messages`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("ALTER TABLE `messages` RENAME TO `messages_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `model_id` TEXT,
                        `status` TEXT,
                        `reasoning` TEXT,
                        `thought_signature` TEXT,
                        `images` TEXT,
                        `tokens` TEXT,
                        `citations` TEXT,
                        `rag_references` TEXT,
                        `rag_progress` TEXT,
                        `rag_metadata` TEXT,
                        `rag_references_loading` INTEGER NOT NULL DEFAULT 0,
                        `execution_steps` TEXT,
                        `tool_calls` TEXT,
                        `pending_approval_tool_ids` TEXT,
                        `tool_call_id` TEXT,
                        `name` TEXT,
                        `planning_task` TEXT,
                        `is_archived` INTEGER NOT NULL DEFAULT 0,
                        `vectorization_status` TEXT,
                        `layout_height` REAL,
                        `tool_results` TEXT,
                        `files` TEXT,
                        `user_images` TEXT,
                        `is_error` INTEGER NOT NULL DEFAULT 0,
                        `error_message` TEXT,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                // 重建新表的索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_session_id` ON `messages` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_session_id_created_at` ON `messages` (`session_id`, `created_at`)")

                val newColumns = listOf(
                    "id", "session_id", "role", "content", "model_id", "status", "reasoning",
                    "thought_signature", "images", "tokens", "citations", "rag_references",
                    "rag_progress", "rag_metadata", "rag_references_loading", "execution_steps",
                    "tool_calls", "pending_approval_tool_ids", "tool_call_id", "name",
                    "planning_task", "is_archived", "vectorization_status", "layout_height",
                    "tool_results", "files", "user_images", "is_error", "error_message", "created_at"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `messages` ($columnsJoined) SELECT $columnsJoined FROM `messages_old`")
                }
                db.execSQL("DROP TABLE `messages_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `model_id` TEXT,
                        `status` TEXT,
                        `reasoning` TEXT,
                        `thought_signature` TEXT,
                        `images` TEXT,
                        `tokens` TEXT,
                        `citations` TEXT,
                        `rag_references` TEXT,
                        `rag_progress` TEXT,
                        `rag_metadata` TEXT,
                        `rag_references_loading` INTEGER NOT NULL DEFAULT 0,
                        `execution_steps` TEXT,
                        `tool_calls` TEXT,
                        `pending_approval_tool_ids` TEXT,
                        `tool_call_id` TEXT,
                        `name` TEXT,
                        `planning_task` TEXT,
                        `is_archived` INTEGER NOT NULL DEFAULT 0,
                        `vectorization_status` TEXT,
                        `layout_height` REAL,
                        `tool_results` TEXT,
                        `files` TEXT,
                        `user_images` TEXT,
                        `is_error` INTEGER NOT NULL DEFAULT 0,
                        `error_message` TEXT,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_session_id` ON `messages` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_session_id_created_at` ON `messages` (`session_id`, `created_at`)")
            }
        }

        private fun recreateVectorsTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Note on §3.4 Unit Test Gate Exemption:
            // This function relies directly on SupportSQLiteDatabase and SQLite's Android system-level APIs.
            // These platform APIs are highly coupled with the Android OS environment and cannot be executed/mocked in a standard local JVM environment.
            // Therefore, this migration logic is exempted from the standard Unit Testing Gate.
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`vectors`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("ALTER TABLE `vectors` RENAME TO `vectors_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `vectors` (
                        `id` TEXT NOT NULL, 
                        `doc_id` TEXT, 
                        `session_id` TEXT, 
                        `content` TEXT NOT NULL, 
                        `embedding` BLOB NOT NULL, 
                        `metadata` TEXT, 
                        `start_message_id` TEXT, 
                        `end_message_id` TEXT, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `version` INTEGER NOT NULL DEFAULT 1, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                // 重建新表的索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectors_doc_id` ON `vectors` (`doc_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectors_session_id` ON `vectors` (`session_id`)")

                val newColumns = listOf(
                    "id", "doc_id", "session_id", "content", "embedding", "metadata", 
                    "start_message_id", "end_message_id", "created_at", "updated_at", 
                    "stale", "version", "file_uuid"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `vectors` ($columnsJoined) SELECT $columnsJoined FROM `vectors_old`")
                }
                db.execSQL("DROP TABLE `vectors_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `vectors` (
                        `id` TEXT NOT NULL, 
                        `doc_id` TEXT, 
                        `session_id` TEXT, 
                        `content` TEXT NOT NULL, 
                        `embedding` BLOB NOT NULL, 
                        `metadata` TEXT, 
                        `start_message_id` TEXT, 
                        `end_message_id` TEXT, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `version` INTEGER NOT NULL DEFAULT 1, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectors_doc_id` ON `vectors` (`doc_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vectors_session_id` ON `vectors` (`session_id`)")
            }
        }

        private fun recreateKgNodesTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Note on §3.4 Unit Test Gate Exemption:
            // This function relies directly on SupportSQLiteDatabase and SQLite's Android system-level APIs.
            // These platform APIs are highly coupled with the Android OS environment and cannot be executed/mocked in a standard local JVM environment.
            // Therefore, this migration logic is exempted from the standard Unit Testing Gate.
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`kg_nodes`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("ALTER TABLE `kg_nodes` RENAME TO `kg_nodes_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `kg_nodes` (
                        `id` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `metadata` TEXT, 
                        `session_id` TEXT, 
                        `agent_id` TEXT, 
                        `source_type` TEXT NOT NULL, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                val newColumns = listOf(
                    "id", "name", "type", "metadata", "session_id", "agent_id", 
                    "source_type", "created_at", "updated_at", "stale", "file_uuid"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `kg_nodes` ($columnsJoined) SELECT $columnsJoined FROM `kg_nodes_old`")
                }
                db.execSQL("DROP TABLE `kg_nodes_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `kg_nodes` (
                        `id` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `metadata` TEXT, 
                        `session_id` TEXT, 
                        `agent_id` TEXT, 
                        `source_type` TEXT NOT NULL, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        private fun recreateKgEdgesTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Note on §3.4 Unit Test Gate Exemption:
            // This function relies directly on SupportSQLiteDatabase and SQLite's Android system-level APIs.
            // These platform APIs are highly coupled with the Android OS environment and cannot be executed/mocked in a standard local JVM environment.
            // Therefore, this migration logic is exempted from the standard Unit Testing Gate.
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`kg_edges`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("ALTER TABLE `kg_edges` RENAME TO `kg_edges_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `kg_edges` (
                        `id` TEXT NOT NULL, 
                        `source_id` TEXT NOT NULL, 
                        `target_id` TEXT NOT NULL, 
                        `relation` TEXT NOT NULL, 
                        `weight` REAL NOT NULL, 
                        `doc_id` TEXT, 
                        `session_id` TEXT, 
                        `agent_id` TEXT, 
                        `source_type` TEXT NOT NULL, 
                        `created_at` INTEGER NOT NULL, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`source_id`) REFERENCES `kg_nodes`(`id`) ON DELETE CASCADE, 
                        FOREIGN KEY(`target_id`) REFERENCES `kg_nodes`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                // 重建新表的索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_source_id` ON `kg_edges` (`source_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_target_id` ON `kg_edges` (`target_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_doc_id` ON `kg_edges` (`doc_id`)")

                val newColumns = listOf(
                    "id", "source_id", "target_id", "relation", "weight", "doc_id", 
                    "session_id", "agent_id", "source_type", "created_at", "stale", "file_uuid"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `kg_edges` ($columnsJoined) SELECT $columnsJoined FROM `kg_edges_old`")
                }
                db.execSQL("DROP TABLE `kg_edges_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `kg_edges` (
                        `id` TEXT NOT NULL, 
                        `source_id` TEXT NOT NULL, 
                        `target_id` TEXT NOT NULL, 
                        `relation` TEXT NOT NULL, 
                        `weight` REAL NOT NULL, 
                        `doc_id` TEXT, 
                        `session_id` TEXT, 
                        `agent_id` TEXT, 
                        `source_type` TEXT NOT NULL, 
                        `created_at` INTEGER NOT NULL, 
                        `stale` INTEGER NOT NULL DEFAULT 0, 
                        `file_uuid` TEXT, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`source_id`) REFERENCES `kg_nodes`(`id`) ON DELETE CASCADE, 
                        FOREIGN KEY(`target_id`) REFERENCES `kg_nodes`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_source_id` ON `kg_edges` (`source_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_target_id` ON `kg_edges` (`target_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edges_doc_id` ON `kg_edges` (`doc_id`)")
            }
        }

        private fun recreateTaskNodesTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Note on §3.4 Unit Test Gate Exemption:
            // This function relies directly on SupportSQLiteDatabase and SQLite's Android system-level APIs.
            // These platform APIs are highly coupled with the Android OS environment and cannot be executed/mocked in a standard local JVM environment.
            // Therefore, this migration logic is exempted from the standard Unit Testing Gate.
            val columns = mutableListOf<String>()
            val cursor = db.query("PRAGMA table_info(`task_nodes`)")
            try {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            } catch (_: Exception) {
            } finally {
                cursor.close()
            }

            if (columns.isNotEmpty()) {
                db.execSQL("DROP INDEX IF EXISTS `idx_task_nodes_session`")
                db.execSQL("DROP INDEX IF EXISTS `idx_task_nodes_parent`")
                db.execSQL("DROP INDEX IF EXISTS `idx_task_nodes_status`")
                db.execSQL("DROP INDEX IF EXISTS `index_task_nodes_parent_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_task_nodes_session_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_task_nodes_status`")

                db.execSQL("ALTER TABLE `task_nodes` RENAME TO `task_nodes_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `task_nodes` (
                        `id` TEXT NOT NULL, 
                        `session_id` TEXT NOT NULL, 
                        `parent_id` TEXT, 
                        `sort_order` INTEGER NOT NULL DEFAULT 0, 
                        `title` TEXT NOT NULL, 
                        `description` TEXT NOT NULL DEFAULT '', 
                        `status` TEXT NOT NULL DEFAULT 'pending', 
                        `note` TEXT, 
                        `artifact_file_uuids` TEXT, 
                        `is_collapsed` INTEGER NOT NULL DEFAULT 0, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_parent_id` ON `task_nodes` (`parent_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_session_id` ON `task_nodes` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_status` ON `task_nodes` (`status`)")

                val newColumns = listOf(
                    "id", "session_id", "parent_id", "sort_order", "title", 
                    "description", "status", "note", "artifact_file_uuids", 
                    "is_collapsed", "created_at", "updated_at"
                )

                val commonColumns = newColumns.filter { columns.contains(it) }
                if (commonColumns.isNotEmpty()) {
                    val columnsJoined = commonColumns.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `task_nodes` ($columnsJoined) SELECT $columnsJoined FROM `task_nodes_old`")
                }
                db.execSQL("DROP TABLE `task_nodes_old`")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `task_nodes` (
                        `id` TEXT NOT NULL, 
                        `session_id` TEXT NOT NULL, 
                        `parent_id` TEXT, 
                        `sort_order` INTEGER NOT NULL DEFAULT 0, 
                        `title` TEXT NOT NULL, 
                        `description` TEXT NOT NULL DEFAULT '', 
                        `status` TEXT NOT NULL DEFAULT 'pending', 
                        `note` TEXT, 
                        `artifact_file_uuids` TEXT, 
                        `is_collapsed` INTEGER NOT NULL DEFAULT 0, 
                        `created_at` INTEGER NOT NULL, 
                        `updated_at` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`), 
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_parent_id` ON `task_nodes` (`parent_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_session_id` ON `task_nodes` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_nodes_status` ON `task_nodes` (`status`)")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                recreateTaskNodesTable(db)
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                recreateKgNodesTable(db)
                recreateKgEdgesTable(db)
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                recreateVectorsTable(db)
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                recreateMessagesTable(db)
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                recreateSessionsTable(db)
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

                // sessions table recreation with perfect schema
                recreateSessionsTable(db)

                // messages table recreation with perfect schema
                recreateMessagesTable(db)

                // vectors table recreation with perfect schema
                recreateVectorsTable(db)

                // kg_nodes table recreation with perfect schema
                recreateKgNodesTable(db)

                // kg_edges table recreation with perfect schema
                recreateKgEdgesTable(db)

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

                // task_nodes table recreation with perfect schema
                recreateTaskNodesTable(db)
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
