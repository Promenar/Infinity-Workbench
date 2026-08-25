package com.promenar.nexara.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
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
        Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()

        assertThat(LegacyDatabasePromoter.promote(context))
            .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
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
