package com.promenar.nexara.data.local.db.recovery

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class DualDatabaseGraphMergerTest {
    @Test
    fun `same ids remain distinct and all known graph references follow legacy mapping`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val current = context.getDatabasePath("merge-current-${System.nanoTime()}.db").toPath()
        val legacy = context.getDatabasePath("merge-legacy-${System.nanoTime()}.db").toPath()
        createSchema(current)
        createSchema(legacy)
        SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            seed(db, "current")
        }
        SQLiteDatabase.openDatabase(legacy.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            seed(db, "legacy")
        }

        val result = DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value })
            .merge(current, legacy, "test-transaction")

        SQLiteDatabase.openDatabase(current.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertThat(count(db, "sessions")).isEqualTo(2)
            assertThat(count(db, "messages")).isEqualTo(4)
            val legacySession = RecoveryStableId.map("sessions", "id", "same-session")
            val legacyParent = RecoveryStableId.map("messages", "id", "parent")
            db.rawQuery("SELECT parent_message_id FROM messages WHERE id=?", arrayOf(RecoveryStableId.map("messages", "id", "child"))).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(legacyParent)
            }
            db.rawQuery("SELECT session_id FROM messages WHERE id=?", arrayOf(legacyParent)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(legacySession)
            }
            db.rawQuery("SELECT file_uuid,workspace_root_uuid,created_by_session_id FROM file_versions WHERE id=?",
                arrayOf(RecoveryStableId.map("file_versions", "id", "version"))).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(RecoveryStableId.map("workspace_files", "uuid", "file"))
                assertThat(it.getString(1)).isEqualTo(RecoveryStableId.map("workspace_files", "uuid", "root"))
                assertThat(it.getString(2)).isEqualTo(legacySession)
            }
            db.rawQuery("SELECT status FROM vectorization_tasks ORDER BY id", null).use { cursor ->
                val statuses = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(statuses).hasSize(4)
                assertThat(statuses.toSet()).containsExactly("failed")
            }
            db.rawQuery("SELECT COUNT(*) FROM vectorization_tasks WHERE sub_status=?", arrayOf(DUAL_DATABASE_RECOVERY_INDEX_HOLD)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(2)
            }
            db.rawQuery("SELECT status FROM tool_execution_ledger ORDER BY session_id", null).use { cursor ->
                assertThat(buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }).containsExactly("FAILED", "FAILED")
            }
            db.rawQuery("SELECT images FROM messages WHERE id=?", arrayOf(legacyParent)).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("data:image/png;base64,AA==")
            }
            assertThat(count(db, "kg_jit_cache")).isEqualTo(0)
        }
        assertThat(result.mappingCount).isGreaterThan(0)
        assertThat(result.unresolvedReferences.filter { it.startsWith("required:") }).isEmpty()
        Files.deleteIfExists(current); Files.deleteIfExists(legacy)
    }

    private fun createSchema(path: java.nio.file.Path) {
        Files.createDirectories(path.parent)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { db ->
            listOf(
                "CREATE TABLE agents(id TEXT PRIMARY KEY, skill_ids TEXT, mcp_server_ids TEXT)",
                "CREATE TABLE custom_skills(id TEXT PRIMARY KEY)",
                "CREATE TABLE mcp_servers(id TEXT PRIMARY KEY)",
                "CREATE TABLE sessions(id TEXT PRIMARY KEY, agent_id TEXT, active_skill_ids TEXT, active_mcp_server_ids TEXT, workspace_root_uuid TEXT, active_task_tree_id TEXT, workspace_path TEXT, approval_request TEXT, pending_intervention TEXT, loop_status TEXT NOT NULL, rag_options TEXT)",
                "CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT NOT NULL, parent_message_id TEXT, rag_references TEXT, content TEXT, images TEXT, FOREIGN KEY(session_id) REFERENCES sessions(id))",
                "CREATE TABLE attachments(id TEXT PRIMARY KEY, message_id TEXT)",
                "CREATE TABLE vectors(id TEXT PRIMARY KEY, session_id TEXT, content TEXT, doc_id TEXT, file_uuid TEXT, start_message_id TEXT, end_message_id TEXT)",
                "CREATE VIRTUAL TABLE vectors_fts USING fts4(content)",
                "CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, workspace_root_uuid TEXT, parent_uuid TEXT, last_write_session_id TEXT, locked_by_session_id TEXT, original_parent_uuid TEXT, physical_root_path TEXT, name TEXT NOT NULL, mime_type TEXT, hash TEXT NOT NULL, is_directory INTEGER NOT NULL, in_recycle_bin INTEGER NOT NULL, vectorized_at INTEGER, updated_at INTEGER NOT NULL)",
                "CREATE TABLE task_nodes(id TEXT PRIMARY KEY, session_id TEXT, parent_id TEXT, artifact_file_uuids TEXT)",
                "CREATE TABLE file_versions(id TEXT PRIMARY KEY, file_uuid TEXT, workspace_root_uuid TEXT, created_by_session_id TEXT, content_path TEXT)",
                "CREATE TABLE workspace_seq(date_key TEXT PRIMARY KEY,last_seq INTEGER NOT NULL)",
                "CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY,type TEXT NOT NULL,status TEXT NOT NULL,doc_id TEXT,doc_title TEXT,workspace_root_uuid TEXT,last_chunk_index INTEGER NOT NULL,progress REAL NOT NULL,error TEXT,skip_vectorization INTEGER NOT NULL,sub_status TEXT,source_mime_type TEXT,content_truncated INTEGER NOT NULL,target_content_hash TEXT,target_epoch INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)",
                "CREATE TABLE tool_execution_ledger(session_id TEXT,assistant_message_id TEXT,tool_call_id TEXT,status TEXT,error TEXT,PRIMARY KEY(session_id,assistant_message_id,tool_call_id))",
                "CREATE TABLE kg_jit_cache(cache_key TEXT PRIMARY KEY,payload TEXT)",
                "CREATE TABLE workspace_mutations(operation_id TEXT PRIMARY KEY)",
            ).forEach(db::execSQL)
        }
    }

    private fun seed(db: SQLiteDatabase, label: String) {
        db.execSQL("INSERT INTO agents VALUES('same-agent','[]','[]')")
        db.execSQL("INSERT INTO sessions VALUES('same-session','same-agent','[]','[]','root','task',?,NULL,NULL,'running','{\"activeDocIds\":[\"file\"],\"activeFolderIds\":[\"root\"]}')", arrayOf("/$label"))
        db.execSQL("INSERT INTO messages VALUES('parent','same-session',NULL,'[]',?,'data:image/png;base64,AA==')", arrayOf("$label parent"))
        db.execSQL("INSERT INTO messages VALUES('child','same-session','parent','[]',?,NULL)", arrayOf("$label child"))
        db.execSQL("INSERT INTO workspace_files VALUES('root','root',NULL,'same-session',NULL,NULL,?,'root',NULL,'root-hash',1,0,NULL,1)", arrayOf("/$label"))
        db.execSQL("INSERT INTO workspace_files VALUES('file','root','root','same-session',NULL,NULL,?,'file.txt','text/plain','file-hash',0,0,NULL,2)", arrayOf("/$label"))
        db.execSQL("INSERT INTO task_nodes VALUES('task','same-session',NULL,'[\"file\"]')")
        db.execSQL("INSERT INTO file_versions VALUES('version','file','root','same-session',?)", arrayOf("/$label/version"))
        db.execSQL("INSERT INTO workspace_seq VALUES('2026-09-22',1)")
        db.execSQL("INSERT INTO vectorization_tasks VALUES('index','memory','pending',NULL,NULL,NULL,0,1,NULL,0,NULL,NULL,0,NULL,0,1,1)")
        db.execSQL("INSERT INTO tool_execution_ledger VALUES('same-session','parent','tool','RUNNING',NULL)")
        db.execSQL("INSERT INTO kg_jit_cache VALUES(?,?)", arrayOf("cache-$label", label))
    }

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst(); it.getInt(0)
    }
}
