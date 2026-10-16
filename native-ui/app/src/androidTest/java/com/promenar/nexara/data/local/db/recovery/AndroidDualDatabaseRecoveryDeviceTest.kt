package com.promenar.nexara.data.local.db.recovery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.nexara.data.local.db.NexaraDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Rule

/** 只操作每次运行新建的私有测试目录；真实 Room、SQLite、Keystore 与文件系统共同参与。 */
class AndroidDualDatabaseRecoveryDeviceTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NexaraDatabase::class.java)

    @Test
    fun publicV17AndV2RecoverBothHistoriesAndRestartKeepsNewWrites() = verifyRecovery(2)

    @Test
    fun publicV17AndInternalV5RecoverBothHistoriesAndRestartKeepsNewWrites() = verifyRecovery(5)

    private fun verifyRecovery(currentVersion: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.randomUUID().toString()
        val root = Files.createDirectory(instrumentation.targetContext.filesDir.toPath().resolve("dual-db-device-$runId"))
        val keyAlias = "nexara.dual-db.device-test.$runId"
        val context = IsolatedContext(instrumentation.targetContext, root.toFile())
        try {
            val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
            val current = context.getDatabasePath(CURRENT_DATABASE_NAME)
            createPublishedFixture(17, legacy, "旧侧中文😀")
            if (currentVersion == 2) createPublishedFixture(2, current, "新侧中文😀")
            else {
                migrationHelper.createDatabase(current.path, 5).close()
                SQLiteDatabase.openDatabase(current.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                    seed(database, "agents", mapOf("id" to "same-agent", "name" to "合成测试"))
                    seed(database, "sessions", mapOf("id" to "same-session", "agent_id" to "same-agent", "title" to "新侧中文😀"))
                    seed(database, "messages", mapOf("id" to "same-message", "session_id" to "same-session", "role" to "user", "content" to "新侧中文😀"))
                }
            }
            val legacyHash = digest(legacy.toPath())
            val currentHash = digest(current.toPath())
            val journal = FileDualDatabaseRecoveryJournal(context.noBackupFilesDir.toPath(), AndroidRestoreJournalAuthenticator(keyAlias))
            val store = RecoverySnapshotStore(setOf(legacy.parentFile!!.toPath(), context.filesDir.toPath()), 10_000, 64L * 1024 * 1024, context.dataDir.toPath())
            val bootstrap = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(), AndroidDualDatabaseSourceProbe(context), journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, store),
            )
            val offer = bootstrap.inspectOrResume()
            assertThat(offer).isInstanceOf(DualDatabaseBootstrapResult.RecoveryRequired::class.java)
            offer as DualDatabaseBootstrapResult.RecoveryRequired
            assertThat(offer.offer.legacyVersion).isEqualTo(17)
            assertThat(offer.offer.currentVersion).isEqualTo(currentVersion)
            assertThat(digest(legacy.toPath())).isEqualTo(legacyHash)
            assertThat(digest(current.toPath())).isEqualTo(currentHash)

            val recovered = bootstrap.recover(offer.offer.offerToken)
            assertThat(recovered).isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)
            val record = checkNotNull(journal.read())
            assertThat(record.stage).isEqualTo(DualDatabaseRecoveryStage.COMPLETE)
            assertThat(digest(legacy.toPath())).isEqualTo(legacyHash)
            val snapshot = context.noBackupFilesDir.toPath().resolve("dual-db-source-archives-v1").resolve(record.transactionId).resolve("files")
            assertThat(digest(snapshot.resolve(LEGACY_DATABASE_NAME))).isEqualTo(legacyHash)
            assertThat(digest(snapshot.resolve(CURRENT_DATABASE_NAME))).isEqualTo(currentHash)

            SQLiteDatabase.openDatabase(current.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                assertThat(database.version).isEqualTo(18)
                database.rawQuery("SELECT m.content FROM messages m JOIN sessions s ON s.id=m.session_id JOIN agents a ON a.id=s.agent_id ORDER BY m.content", null).use { cursor ->
                    val content = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    assertThat(content).containsExactly("旧侧中文😀", "新侧中文😀")
                }
                database.rawQuery("PRAGMA foreign_key_check", null).use { assertThat(it.count).isEqualTo(0) }
                seed(database, "messages", mapOf("id" to "after-recovery", "session_id" to "same-session", "role" to "user", "content" to "恢复后新消息"))
            }
            val beforeRestart = digest(current.toPath())
            val restarted = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(), AndroidDualDatabaseSourceProbe(context), journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, store),
            ).inspectOrResume()
            assertThat(restarted).isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)
            assertThat(digest(current.toPath())).isEqualTo(beforeRestart)
            SQLiteDatabase.openDatabase(current.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT content FROM messages WHERE id='after-recovery'", null).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("恢复后新消息")
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
            root.toFile().deleteRecursively()
        }
    }

    private fun createPublishedFixture(version: Int, path: File, content: String) {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("public-v$version.json").use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            fixture.getValue("statements").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
            database.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)", arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content))
            database.version = version
            seed(database, "agents", mapOf("id" to "same-agent", "name" to "合成测试"))
            seed(database, "sessions", mapOf("id" to "same-session", "agent_id" to "same-agent", "title" to content))
            seed(database, "messages", buildMap {
                put("id", "same-message"); put("session_id", "same-session"); put("role", "user"); put("content", content)
                if (version == 17) put("attachments", "[]")
            })
        }
    }

    private fun seed(database: SQLiteDatabase, table: String, fields: Map<String, String>) {
        val values = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                val value = fields[column]
                if (value != null) values.put(column, value)
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    when (cursor.getString(2).uppercase()) {
                        "INTEGER", "REAL" -> values.put(column, 0)
                        "BLOB" -> values.put(column, byteArrayOf())
                        else -> values.put(column, "")
                    }
                }
            }
        }
        database.insertOrThrow(table, null, values)
    }

    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = File(root, "databases").apply { check(mkdir()) }
        private val files = File(root, "files").apply { check(mkdir()) }
        private val noBackup = File(root, "no_backup").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
    }
}
