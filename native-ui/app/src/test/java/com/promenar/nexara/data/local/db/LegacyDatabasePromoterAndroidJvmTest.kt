package com.promenar.nexara.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LegacyDatabasePromoterAndroidJvmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("nexara.db")
        context.deleteDatabase("nexara_v2.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nexara.db")
        context.deleteDatabase("nexara_v2.db")
    }

    @Test
    fun productionPromotionCheckpointsValidatesAndCopiesHistoricalDatabase() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara.db"), null).use { sqlite ->
            sqlite.enableWriteAheadLogging()
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, attachments TEXT)")
            sqlite.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')",
            )
            sqlite.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO sentinel(value) VALUES('preserved')")
            sqlite.version = 17
        }

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.Promoted)
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
        assertThat(context.getDatabasePath("nexara_v2.db").isFile).isTrue()

        SQLiteDatabase.openDatabase(
            context.getDatabasePath("nexara_v2.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertThat(sqlite.version).isEqualTo(17)
            sqlite.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("preserved")
            }
        }
    }

    @Test
    fun productionPromotionRejectsWrongIdentityCurrentDatabaseInsteadOfShadowingHistoricalData() {
        createValidPublishedLegacyDatabase()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara_v2.db"), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, name_customized INTEGER NOT NULL)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, workspace_root_uuid TEXT NOT NULL)")
            sqlite.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY, workspace_root_uuid TEXT)")
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,'wrong-current-identity')")
            sqlite.version = 5
        }

        assertThrows(java.io.IOException::class.java) {
            LegacyDatabasePromoter.promote(context)
        }
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionAcceptsExactCurrentV18DatabaseWhenHistoricalSourceAlsoExists() {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraV18RecoveryDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionAcceptsExactCurrentV21DatabaseWhenHistoricalSourceAlsoExists() {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionRejectsV21WithoutUniqueSshAliasIndex() {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.execSQL("DROP INDEX index_ssh_hosts_alias") }

        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionRejectsV21WithPartialSshAliasIndex() = assertSshContractRejected { sqlite ->
        sqlite.execSQL("DROP INDEX index_ssh_hosts_alias")
        sqlite.execSQL("CREATE UNIQUE INDEX index_ssh_hosts_alias ON ssh_hosts(alias) WHERE alias='restricted-only'")
    }

    @Test
    fun productionPromotionRejectsV21WithNullableSshAlias() = assertSshColumnRejected(
        "alias TEXT NOT NULL", "alias TEXT",
    )

    @Test
    fun productionPromotionRejectsV21WithSshPortTypeDrift() = assertSshColumnRejected(
        "port INTEGER NOT NULL DEFAULT 22", "port TEXT NOT NULL DEFAULT 22",
    )

    @Test
    fun productionPromotionRejectsV21WithSshPortDefaultDrift() = assertSshColumnRejected(
        "port INTEGER NOT NULL DEFAULT 22", "port INTEGER NOT NULL DEFAULT 2222",
    )

    @Test
    fun productionPromotionRejectsV21WithSshPrimaryKeyDrift() = assertSshColumnRejected(
        "PRIMARY KEY(id)", "PRIMARY KEY(alias)",
    )

    @Test
    fun productionPromotionRejectsV21WithUnknownIdentity() {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.execSQL("UPDATE room_master_table SET identity_hash='unknown' WHERE id=42") }

        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionAcceptsExactV18DatabaseBeforeRoomUpgrade() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(18)

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { sqlite -> assertThat(sqlite.version).isEqualTo(18) }
    }

    @Test
    fun productionPromotionAcceptsExactV19DatabaseBeforeRoomUpgrade() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(19)

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { sqlite -> assertThat(sqlite.version).isEqualTo(19) }
    }

    @Test
    fun productionPromotionAcceptsExactV20DatabaseBeforeRoomUpgrade() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(20)
        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { sqlite -> assertThat(sqlite.version).isEqualTo(20) }
    }

    @Test
    fun productionPromotionRejectsV21IdentityWithV20Structure() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(20)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { sqlite ->
                sqlite.version = 21
                sqlite.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(NEXARA_SCHEMA_V21_IDENTITY_HASH))
            }
        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
    }

    @Test
    fun productionPromotionRejectsV19IdentityWithV20Header() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(19)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { sqlite -> sqlite.version = 20 }

        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
    }

    @Test
    fun productionPromotionRejectsV20MissingExternalSnapshotTableDespiteMatchingIdentity() {
        createValidPublishedLegacyDatabase()
        createCurrentDatabase(20)
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { sqlite -> sqlite.execSQL("DROP TABLE external_snapshots") }

        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
    }

    @Test
    fun productionPromotionRejectsV18IdentityWithV19Header() {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("nexara_v2.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL(
                "UPDATE room_master_table SET identity_hash='c32f59706ea482fd5697681569c6fdb5' WHERE id=42",
            )
            sqlite.version = 19
        }

        assertThrows(java.io.IOException::class.java) {
            LegacyDatabasePromoter.promote(context)
        }
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    @Test
    fun productionPromotionRejectsAmbiguousExactV5AndV17DatabasesWithoutMutatingEither() {
        createValidPublishedLegacyDatabase("legacy-sentinel")
        createExactCurrentV5Database("current-sentinel")

        assertThrows(java.io.IOException::class.java) {
            LegacyDatabasePromoter.promote(context)
        }

        assertThat(readSentinel("nexara.db")).isEqualTo("legacy-sentinel")
        assertThat(readSentinel("nexara_v2.db")).isEqualTo("current-sentinel")
    }

    private fun createCurrentDatabase(version: Int) {
        val schema = Json.parseToJsonElement(
            File("app/schemas/com.promenar.nexara.data.local.db.NexaraDatabase/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara_v2.db"), null).use { sqlite ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    sqlite.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
                entity["contentSyncTriggers"]?.jsonArray?.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.version = version
        }
    }

    private fun createValidPublishedLegacyDatabase(sentinel: String? = null) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara.db"), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, attachments TEXT)")
            sqlite.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')",
            )
            sentinel?.let {
                sqlite.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
                sqlite.execSQL("INSERT INTO sentinel(value) VALUES(?)", arrayOf(it))
            }
            sqlite.version = 17
        }
    }

    private fun assertSshContractRejected(mutate: (SQLiteDatabase) -> Unit) {
        createValidPublishedLegacyDatabase()
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("nexara_v2.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use(mutate)
        assertThrows(java.io.IOException::class.java) { LegacyDatabasePromoter.promote(context) }
        assertThat(context.getDatabasePath("nexara.db").isFile).isTrue()
    }

    private fun assertSshColumnRejected(original: String, changed: String) = assertSshContractRejected { sqlite ->
        val sql = """CREATE TABLE ssh_hosts (
            id TEXT NOT NULL, alias TEXT NOT NULL, hostname TEXT NOT NULL,
            port INTEGER NOT NULL DEFAULT 22, username TEXT NOT NULL,
            auth_type TEXT NOT NULL DEFAULT 'KEY', jump_alias TEXT, trusted_key_type TEXT,
            trusted_fingerprint TEXT, keep_terminal INTEGER NOT NULL DEFAULT 0,
            local_forward_port INTEGER, forward_host TEXT NOT NULL DEFAULT '127.0.0.1',
            forward_port INTEGER, PRIMARY KEY(id)
        )""".trimIndent()
        check(sql.contains(original)) { "SSH 测试夹具的变异目标缺失" }
        sqlite.execSQL("DROP TABLE ssh_hosts")
        sqlite.execSQL(sql.replace(original, changed))
        sqlite.execSQL("CREATE UNIQUE INDEX index_ssh_hosts_alias ON ssh_hosts(alias)")
    }

    private fun createExactCurrentV5Database(sentinel: String) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara_v2.db"), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, name_customized INTEGER NOT NULL)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, workspace_root_uuid TEXT NOT NULL)")
            sqlite.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY, workspace_root_uuid TEXT)")
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,'3c6ffe1572c71bac7e866a33388a5a3b')",
            )
            sqlite.execSQL("CREATE TABLE sentinel(value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO sentinel(value) VALUES(?)", arrayOf(sentinel))
            sqlite.version = 5
        }
    }

    private fun readSentinel(databaseName: String): String = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { sqlite ->
        sqlite.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }
}
