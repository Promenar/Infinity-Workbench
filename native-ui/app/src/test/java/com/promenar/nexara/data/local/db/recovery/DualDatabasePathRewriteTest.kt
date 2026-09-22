package com.promenar.nexara.data.local.db.recovery

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DualDatabasePathRewriteTest {
    @Test
    fun `trusted system alias rewrites nested workspace roots without changing adjacent paths`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = context.getDatabasePath("rewrite-alias-${System.nanoTime()}.db").toPath()
        Files.createDirectories(path.parent)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { database ->
            database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, physical_root_path TEXT)")
            database.execSQL("CREATE TABLE file_versions(id TEXT PRIMARY KEY, content_path TEXT)")
            database.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, avatar_path TEXT)")
            database.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY, uri TEXT, local_uri TEXT)")
            database.execSQL("CREATE TABLE artifacts(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, images TEXT, files TEXT, user_images TEXT, legacy_attachments TEXT)")
            database.execSQL("INSERT INTO sessions VALUES('rag','/data/data/app/files/rag_workspace')")
            database.execSQL("INSERT INTO workspace_files VALUES('root','/data/data/app/files/rag_workspace')")
            database.execSQL("INSERT INTO workspace_files VALUES('neighbor','/data/data/app/files-other/rag_workspace')")
        }

        rewriteTrustedAliasPaths(path, mapOf("/data/data/app/files" to "/data/user/0/app/files"))

        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertThat(value(database, "sessions", "workspace_path", "id='rag'"))
                .isEqualTo("/data/user/0/app/files/rag_workspace")
            assertThat(value(database, "workspace_files", "physical_root_path", "uuid='root'"))
                .isEqualTo("/data/user/0/app/files/rag_workspace")
            assertThat(value(database, "workspace_files", "physical_root_path", "uuid='neighbor'"))
                .isEqualTo("/data/data/app/files-other/rag_workspace")
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `root metacharacters do not rewrite adjacent roots`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = context.getDatabasePath("rewrite-${System.nanoTime()}.db").toPath()
        Files.createDirectories(path.parent)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { database ->
            database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, physical_root_path TEXT)")
            database.execSQL("CREATE TABLE file_versions(id TEXT PRIMARY KEY, content_path TEXT)")
            database.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, avatar_path TEXT)")
            database.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY, uri TEXT, local_uri TEXT)")
            database.execSQL("CREATE TABLE artifacts(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, images TEXT, files TEXT, user_images TEXT, legacy_attachments TEXT)")
            database.execSQL("INSERT INTO sessions VALUES('exact','/app/foo_bar')")
            database.execSQL("INSERT INTO workspace_files VALUES('percent','/app/100%')")
            database.execSQL("INSERT INTO file_versions VALUES('child','/app/foo_bar/.versions/a.txt')")
            database.execSQL("INSERT INTO file_versions VALUES('underscore-neighbor','/app/fooXbar/.versions/keep.txt')")
            database.execSQL("INSERT INTO file_versions VALUES('percent-neighbor','/app/1000/.versions/keep.txt')")
            database.execSQL("INSERT INTO attachments VALUES('file-uri','file:///app/foo_bar/a.txt',NULL)")
        }

        rewriteWorkspacePaths(
            path,
            linkedMapOf("/app/foo_bar" to "/recovered/underscore", "/app/100%" to "/recovered/percent"),
        )

        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertThat(value(database, "sessions", "workspace_path", "id='exact'"))
                .isEqualTo("/recovered/underscore")
            assertThat(value(database, "workspace_files", "physical_root_path", "uuid='percent'"))
                .isEqualTo("/recovered/percent")
            assertThat(value(database, "file_versions", "content_path", "id='child'"))
                .isEqualTo("/recovered/underscore/.versions/a.txt")
            assertThat(value(database, "file_versions", "content_path", "id='underscore-neighbor'"))
                .isEqualTo("/app/fooXbar/.versions/keep.txt")
            assertThat(value(database, "file_versions", "content_path", "id='percent-neighbor'"))
                .isEqualTo("/app/1000/.versions/keep.txt")
            assertThat(value(database, "attachments", "uri", "id='file-uri'"))
                .isEqualTo("file:///recovered/underscore/a.txt")
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `nested roots and structured paths rewrite from original value exactly once`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = context.getDatabasePath("rewrite-nested-${System.nanoTime()}.db").toPath()
        Files.createDirectories(path.parent)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { database ->
            database.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, physical_root_path TEXT)")
            database.execSQL("CREATE TABLE file_versions(id TEXT PRIMARY KEY, content_path TEXT)")
            database.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, avatar_path TEXT)")
            database.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY, uri TEXT, local_uri TEXT)")
            database.execSQL("CREATE TABLE artifacts(id TEXT PRIMARY KEY, workspace_path TEXT)")
            database.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, images TEXT, files TEXT, user_images TEXT, legacy_attachments TEXT)")
            database.execSQL("INSERT INTO file_versions VALUES('nested','/app/workspaces/old/.versions/a.txt')")
            database.execSQL(
                "INSERT INTO messages VALUES('json',NULL,?,NULL,?)",
                arrayOf(
                    "[{\"path\":\"/app/workspaces/old/doc.txt\"}]",
                    "[{\"uri\":\"file:///app/avatar.bin\",\"sizeBytes\":12}]",
                ),
            )
        }

        rewriteWorkspacePaths(
            path,
            linkedMapOf(
                "/app/workspaces/old" to "/app/workspaces/recovered-current",
                "/app/avatar.bin" to "/app/recovered-assets/current/avatar.bin",
                "/app" to "/no-backup/archive",
            ),
        )

        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertThat(value(database, "file_versions", "content_path", "id='nested'"))
                .isEqualTo("/app/workspaces/recovered-current/.versions/a.txt")
            assertThat(value(database, "messages", "files", "id='json'"))
                .contains("/app/workspaces/recovered-current/doc.txt")
            assertThat(value(database, "messages", "legacy_attachments", "id='json'"))
                .contains("file:///app/recovered-assets/current/avatar.bin")
            val attachment = Json.parseToJsonElement(
                value(database, "messages", "legacy_attachments", "id='json'"),
            ).jsonArray.single().jsonObject
            assertThat(attachment.getValue("sizeBytes").jsonPrimitive.isString).isFalse()
            assertThat(attachment.getValue("sizeBytes").jsonPrimitive.content).isEqualTo("12")
        }
        Files.deleteIfExists(path)
    }

    private fun value(database: SQLiteDatabase, table: String, column: String, where: String): String =
        database.rawQuery("SELECT $column FROM $table WHERE $where", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
