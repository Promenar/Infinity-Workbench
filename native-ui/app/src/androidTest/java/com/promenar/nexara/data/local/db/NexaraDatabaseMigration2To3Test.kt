package com.promenar.nexara.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NexaraDatabaseMigration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NexaraDatabase::class.java,
    )

    @Test
    fun migration2To3PreservesRowsAndAddsFailClosedContracts() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                """INSERT INTO agents(
                    id,name,description,name_customized,description_customized,system_prompt,model,
                    icon,color,is_pinned,created_at,use_inherited_config
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("agent-1", "Agent", "", 0, 0, "", "model", "icon", "color", 0, 1, 1),
            )
            execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?)""",
                arrayOf<Any?>("session-1", "agent-1", "Session", 0, 0, 1, 1),
            )
            execSQL(
                "INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>("message-1", "session-1", "assistant", "kept", 1),
            )
            execSQL(
                """INSERT INTO tool_execution_ledger(
                    session_id,assistant_message_id,tool_call_id,tool_name,requires_approval,status,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("session-1", "message-1", "call-1", "write_file", 1, "SUCCEEDED", 1, 1),
            )
            execSQL(
                """INSERT INTO workspace_files(
                    uuid,workspace_root_uuid,name,hash,size_bytes,is_directory,physical_root_path,
                    materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("root-1", "root-1", "root", "hash", 0, 1, "/tmp/root", "/", 1, 1, 0, 1, 1),
            )
            execSQL(
                """INSERT INTO file_versions(
                    id,file_uuid,workspace_root_uuid,hash,content_path,created_at
                ) VALUES(?,?,?,?,?,?)""",
                arrayOf<Any?>("version-1", "root-1", "root-1", "hash", "/tmp/version", 1),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            18,
            true,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_18,
        ).use { database ->
            assertThat(database.stringQuery("SELECT execution_mode FROM agents WHERE id='agent-1'"))
                .isEqualTo("semi")
            assertThat(database.stringQuery("SELECT skill_ids FROM agents WHERE id='agent-1'"))
                .isEqualTo("[]")
            assertThat(database.stringQuery("SELECT mcp_server_ids FROM agents WHERE id='agent-1'"))
                .isEqualTo("[]")
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='session-1'"))
                .isEqualTo("auto")
            database.execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES('session-2','agent-1','New',0,0,2,2)""",
            )
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='session-2'"))
                .isEqualTo("semi")
            assertThat(database.stringQuery("SELECT content FROM messages WHERE id='message-1'"))
                .isEqualTo("kept")
            assertThat(database.stringQuery("SELECT runtime_tool_id FROM tool_execution_ledger WHERE tool_call_id='call-1'"))
                .isEqualTo("write_file")
            assertThat(database.stringQuery("SELECT arguments_digest FROM tool_execution_ledger WHERE tool_call_id='call-1'"))
                .isEqualTo("")
            assertThat(database.stringQuery("SELECT definition_digest FROM tool_execution_ledger WHERE tool_call_id='call-1'"))
                .isEqualTo("")
            assertThat(database.longQuery("SELECT COUNT(*) FROM file_versions WHERE id='version-1'"))
                .isEqualTo(1)
            assertThat(database.longQuery("SELECT COUNT(*) FROM workspace_mutations"))
                .isEqualTo(0)
        }
    }

    @Test
    fun migration3To4PreservesFrozenV3AgentSessionAndLedger() {
        helper.createDatabase(DATABASE_V3_NAME, 3).apply {
            execSQL(
                """INSERT INTO agents(
                    id,name,description,name_customized,description_customized,system_prompt,model,
                    icon,color,is_pinned,execution_mode,created_at,use_inherited_config
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("agent-v3", "Agent", "", 0, 0, "", "model", "icon", "color", 0, "manual", 1, 1),
            )
            execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,execution_mode,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("session-v3", "agent-v3", "Session", 0, 0, "manual", 1, 1),
            )
            execSQL(
                "INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>("message-v3", "session-v3", "assistant", "kept", 1),
            )
            execSQL(
                """INSERT INTO tool_execution_ledger(
                    session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,
                    arguments_digest,definition_digest,requires_approval,status,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("session-v3", "message-v3", "call-v3", "write_file", "write_file", "args", "definition", 1, "SUCCEEDED", 1, 1),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_V3_NAME,
            18,
            true,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_18,
        ).use { database ->
            assertThat(database.stringQuery("SELECT execution_mode FROM agents WHERE id='agent-v3'"))
                .isEqualTo("manual")
            assertThat(database.stringQuery("SELECT skill_ids FROM agents WHERE id='agent-v3'"))
                .isEqualTo("[]")
            assertThat(database.stringQuery("SELECT mcp_server_ids FROM agents WHERE id='agent-v3'"))
                .isEqualTo("[]")
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='session-v3'"))
                .isEqualTo("manual")
            assertThat(database.stringQuery("SELECT status FROM tool_execution_ledger WHERE tool_call_id='call-v3'"))
                .isEqualTo("SUCCEEDED")
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-2-3"
        const val DATABASE_V3_NAME = "migration-3-4"
    }
}
