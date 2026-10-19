package com.promenar.nexara.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SshRecoveryDatabaseCompatibilityTest {
    @Test
    fun `独立恢复数据库保持真实v18身份且不创建SSH表`() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), NexaraV18RecoveryDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use {
                check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(18)
            }
            sqlite.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("c32f59706ea482fd5697681569c6fdb5")
            }
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ssh_hosts'").use {
                assertThat(it.moveToFirst()).isFalse()
            }
        } finally { database.close() }
    }

    @Test
    fun `保留历史库时Promoter精确验证当前正式版本且不覆盖旧数据`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("nexara.db")
        context.deleteDatabase("nexara_v2.db")
        try {
            context.getDatabasePath("nexara.db").parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara.db"), null).use {
                it.execSQL("CREATE TABLE sentinel(value TEXT)")
                it.execSQL("INSERT INTO sentinel VALUES('historical')")
                it.version = 17
            }
            Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
                .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()

            assertThat(LegacyDatabasePromoter.promote(context)).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
            SQLiteDatabase.openDatabase(context.getDatabasePath("nexara.db").path, null, SQLiteDatabase.OPEN_READONLY)
                .use { sqlite ->
                    sqlite.rawQuery("SELECT value FROM sentinel", null).use {
                        check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("historical")
                    }
                }
        } finally {
            context.deleteDatabase("nexara.db")
            context.deleteDatabase("nexara_v2.db")
        }
    }

    @Test
    fun `旧主库头与当前版本WAL共存时Promoter接受完整新库且源库零写入`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("nexara.db")
        context.deleteDatabase("nexara_v2.db")
        try {
            context.getDatabasePath("nexara.db").parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nexara.db"), null).use {
                it.execSQL("CREATE TABLE agents(id TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, attachments TEXT)")
                it.execSQL("CREATE TABLE workspace_files(uuid TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE vectorization_tasks(id TEXT PRIMARY KEY)")
                it.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
                it.execSQL("INSERT INTO room_master_table VALUES(42,'3311ec5f07e8df42c02fd09163c49f6e')")
                it.version = 17
            }
            Room.databaseBuilder(context, NexaraDatabase::class.java, "nexara_v2.db")
                .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }.close()
            val main = context.getDatabasePath("nexara_v2.db")
            SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.enableWriteAheadLogging()
                sqlite.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { check(it.moveToFirst()) }
                sqlite.version = 5
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0)
                }
                sqlite.version = 22
                RandomAccessFile(main, "r").use { file -> file.seek(60); assertThat(file.readInt()).isEqualTo(5) }
                val before = sourceHashes(context)
                assertThat(before.keys).contains("nexara_v2.db-wal")

                assertThat(LegacyDatabasePromoter.promote(context)).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
                RandomAccessFile(main, "r").use { file -> file.seek(60); assertThat(file.readInt()).isEqualTo(5) }
                assertThat(sourceHashes(context)).isEqualTo(before)
            }
        } finally {
            context.deleteDatabase("nexara.db")
            context.deleteDatabase("nexara_v2.db")
        }
    }

    private fun sourceHashes(context: Context): Map<String, String> =
        listOf("nexara.db", "nexara_v2.db").flatMap { name ->
            listOf(name, "$name-wal", "$name-journal")
        }.map(context::getDatabasePath).filter { it.isFile }.associate { file ->
            file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
}
