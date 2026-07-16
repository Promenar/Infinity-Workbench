package com.promenar.nexara.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NexaraDatabaseMigration1To2Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NexaraDatabase::class.java,
    )

    @Test
    fun migration1To2BackfillsDocumentTargetsAndLeavesLegacyTaskTypesUntargeted() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """INSERT INTO workspace_files(
                    uuid, workspace_root_uuid, name, hash, size_bytes, is_directory,
                    physical_root_path, materialized_path, vector_version, kg_version,
                    in_recycle_bin, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("root", "root", "root", "root-hash", 0, 1, "/tmp/root", "/", 1, 1, 0, 1, 101),
            )
            execSQL(
                """INSERT INTO workspace_files(
                    uuid, workspace_root_uuid, parent_uuid, name, hash, size_bytes, is_directory,
                    physical_root_path, materialized_path, vector_version, kg_version,
                    in_recycle_bin, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>("doc", "root", "root", "doc.txt", "doc-hash", 3, 0, "/tmp/root", "/doc.txt", 1, 1, 0, 1, 202),
            )
            insertTask("document-reference", "document_reference", "root", "doc")
            insertTask("memory", "memory", null, null)
            insertTask("document", "document", null, null)
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, MIGRATION_1_2).use { database ->
            database.query(
                "SELECT id, target_content_hash, target_epoch FROM vectorization_tasks ORDER BY id",
            ).use { cursor ->
                val rows = buildMap<String, Pair<String?, Long>> {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getString(1) to cursor.getLong(2))
                    }
                }
                assertThat(rows.getValue("document-reference")).isEqualTo("doc-hash" to 202L)
                assertThat(rows.getValue("memory")).isEqualTo(null to 0L)
                assertThat(rows.getValue("document")).isEqualTo(null to 0L)
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertTask(
        id: String,
        type: String,
        root: String?,
        doc: String?,
    ) {
        execSQL(
            """INSERT INTO vectorization_tasks(
                id, type, status, workspace_root_uuid, doc_id, last_chunk_index, progress,
                skip_vectorization, content_truncated, created_at, updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(id, type, "pending", root, doc, 0, 0.0, 0, 0, 1, 1),
        )
    }

    private companion object {
        const val DATABASE_NAME = "migration-1-2"
    }
}
