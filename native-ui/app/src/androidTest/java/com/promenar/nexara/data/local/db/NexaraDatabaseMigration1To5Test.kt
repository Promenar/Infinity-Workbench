package com.promenar.nexara.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 冻结旧版 v1 数据一次性跨越全部前进迁移到当前 v5 的发行升级合同。 */
@RunWith(AndroidJUnit4::class)
class NexaraDatabaseMigration1To5Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NexaraDatabase::class.java,
    )

    @Test
    fun migration1To5PreservesUserRowsAndCreatesEveryCurrentContract() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """INSERT INTO agents(
                    id,name,description,name_customized,description_customized,system_prompt,model,
                    icon,color,is_pinned,created_at,use_inherited_config
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("agent-v1", "Legacy Agent", "kept", 1, 1, "prompt", "model", "icon", "color", 1, 10, 1),
            )
            execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?)""",
                arrayOf<Any?>("session-v1", "agent-v1", "Legacy Session", 1, 1, 11, 12),
            )
            execSQL(
                "INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>("message-v1", "session-v1", "user", "legacy message", 13),
            )
            execSQL(
                """INSERT INTO workspace_files(
                    uuid,workspace_root_uuid,name,hash,size_bytes,is_directory,physical_root_path,
                    materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("root-v1", "root-v1", "workspace", "root-hash", 0, 1, "/tmp/legacy", "/", 1, 1, 0, 14, 15),
            )
            execSQL(
                """INSERT INTO workspace_files(
                    uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,
                    materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("doc-v1", "root-v1", "root-v1", "legacy.txt", "doc-hash", 7, 0, "/tmp/legacy", "/legacy.txt", 1, 1, 0, 16, 17),
            )
            execSQL(
                """INSERT INTO vectorization_tasks(
                    id,type,status,workspace_root_uuid,doc_id,last_chunk_index,progress,
                    skip_vectorization,content_truncated,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("task-v1", "document_reference", "pending", "root-v1", "doc-v1", 0, 0.25, 0, 0, 18, 19),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            18,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_18,
        ).use { database ->
            assertThat(database.stringQuery("SELECT name FROM agents WHERE id='agent-v1'"))
                .isEqualTo("Legacy Agent")
            assertThat(database.stringQuery("SELECT execution_mode FROM agents WHERE id='agent-v1'"))
                .isEqualTo("semi")
            assertThat(database.stringQuery("SELECT title FROM sessions WHERE id='session-v1'"))
                .isEqualTo("Legacy Session")
            assertThat(database.stringQuery("SELECT content FROM messages WHERE id='message-v1'"))
                .isEqualTo("legacy message")
            assertThat(database.stringQuery("SELECT name FROM workspace_files WHERE uuid='doc-v1'"))
                .isEqualTo("legacy.txt")
            assertThat(database.stringQuery("SELECT target_content_hash FROM vectorization_tasks WHERE id='task-v1'"))
                .isEqualTo("doc-hash")
            assertThat(database.longQuery("SELECT target_epoch FROM vectorization_tasks WHERE id='task-v1'"))
                .isEqualTo(17L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM workspace_mutations")).isEqualTo(0L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM mcp_tool_snapshots")).isEqualTo(0L)
            assertThat(database.longQuery("PRAGMA foreign_key_check")).isEqualTo(0L)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            if (!cursor.moveToFirst()) return@use 0L
            cursor.getLong(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-1-5"
    }
}
