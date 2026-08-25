package com.promenar.nexara.data.local.db

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyDatabasePromoterInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseDirectory: File

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        databaseDirectory = File(targetContext.cacheDir, "promoter-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(databaseDirectory, name)
        }
    }

    @After
    fun tearDown() {
        databaseDirectory.deleteRecursively()
    }

    @Test
    fun exactV18WithFtsAndRetainedV17UsesCurrentDatabaseWithoutSourceWrites() {
        createPublishedV17Database()
        Room.databaseBuilder(context, NexaraDatabase::class.java, CURRENT_NAME)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()
        File(databaseDirectory, ".nexara-db-validation-stale").apply {
            mkdirs()
            resolve("retained.db").writeText("stale private validation copy")
        }
        val before = sourceHashes()

        val result = LegacyDatabasePromoter.promote(context)

        assertThat(result).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        assertThat(sourceHashes()).isEqualTo(before)
        assertThat(validationDirectories()).isEmpty()
    }

    @Test
    fun ambiguousV5AndV17ValidatesFtsOnCopyAndLeavesBothSourcesUnchanged() {
        createPublishedV17Database()
        createExactV5Database()
        val legacy = SQLiteDatabase.openDatabase(
            context.getDatabasePath(LEGACY_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        assertThat(legacy.enableWriteAheadLogging()).isTrue()
        legacy.execSQL("INSERT INTO vectors_fts(content) VALUES('wal source sentinel')")
        File(context.getDatabasePath(LEGACY_NAME).path + "-journal").writeBytes(ByteArray(512))
        val before = sourceHashes()
        assertThat(before.keys).containsAtLeast("$LEGACY_NAME-wal", "$LEGACY_NAME-journal")

        try {
            val failure = assertThrows(IOException::class.java) {
                LegacyDatabasePromoter.promote(context)
            }

            assertThat(failure).hasMessageThat().contains("无法自动判定权威数据源")
            assertThat(sourceHashes()).isEqualTo(before)
            assertThat(validationDirectories()).isEmpty()
        } finally {
            legacy.close()
        }
    }

    private fun createPublishedV17Database() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(LEGACY_NAME), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, attachments TEXT)")
            sqlite.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY)")
            sqlite.execSQL("CREATE VIRTUAL TABLE vectors_fts USING fts4(content)")
            sqlite.execSQL("INSERT INTO vectors_fts(content) VALUES('legacy fts sentinel')")
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')",
            )
            sqlite.version = 17
        }
    }

    private fun createExactV5Database() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(CURRENT_NAME), null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY, name_customized INTEGER NOT NULL)")
            sqlite.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY)")
            sqlite.execSQL(
                "CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY, workspace_root_uuid TEXT NOT NULL)",
            )
            sqlite.execSQL(
                "CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY, workspace_root_uuid TEXT)",
            )
            sqlite.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,'3c6ffe1572c71bac7e866a33388a5a3b')",
            )
            sqlite.version = 5
        }
    }

    private fun sourceHashes(): Map<String, String> = buildList {
        listOf(LEGACY_NAME, CURRENT_NAME).forEach { name ->
            add(context.getDatabasePath(name))
            listOf("-wal", "-journal").forEach { suffix ->
                context.getDatabasePath(name + suffix).takeIf(File::isFile)?.let(::add)
            }
        }
    }.associate { file ->
        val bytes = file.readBytes()
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .let { digest -> file.name to digest }
    }

    private fun validationDirectories(): List<java.io.File> =
        databaseDirectory.listFiles { file -> file.name.startsWith(".nexara-db-validation-") }
            ?.toList()
            .orEmpty()

    private companion object {
        const val LEGACY_NAME = "nexara.db"
        const val CURRENT_NAME = "nexara_v2.db"
    }
}
