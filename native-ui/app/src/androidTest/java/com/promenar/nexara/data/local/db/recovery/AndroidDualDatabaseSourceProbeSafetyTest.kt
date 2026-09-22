package com.promenar.nexara.data.local.db.recovery

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** 真实Android descriptor/SQLite测试，不以Fake或provider跳过代替路径安全证据。 */
class AndroidDualDatabaseSourceProbeSafetyTest {
    @Test fun routingOnlyV18HeaderSkipsProbeButPromoterStillRejectsWrongIdentity() = fixture { context, root ->
        val current = context.getDatabasePath(CURRENT_DATABASE_NAME).toPath()
        java.nio.channels.FileChannel.open(current, StandardOpenOption.WRITE).use {
            it.write(java.nio.ByteBuffer.allocate(4).putInt(18).apply { flip() }, 60)
            it.force(true)
        }
        val before = sourceHashes(context)
        assertThat(AndroidDualDatabaseSourceProbe(context).inspectDualSources()).isNull()
        assertThat(Files.exists(root.resolve("no_backup/dual-db-probes-v1"))).isFalse()
        assertThrows(java.io.IOException::class.java) {
            com.promenar.nexara.data.local.db.LegacyDatabasePromoter.promote(context)
        }
        assertThat(sourceHashes(context)).isEqualTo(before)
    }

    @Test fun retainedProbeLimitStopsWithoutDeletingUnknownContent() = fixture { context, root ->
        val base = Files.createDirectory(root.resolve("no_backup/dual-db-probes-v1"))
        listOf("retained-a", "retained-b").forEach {
            Files.write(Files.createDirectory(base.resolve(it)).resolve("keep.txt"), "保留现场".toByteArray())
        }
        assertThrows(IllegalStateException::class.java) { AndroidDualDatabaseSourceProbe(context).inspectDualSources() }
        assertThat(Files.list(base).use { it.count() }).isEqualTo(2L)
        assertThat(Files.readAllBytes(base.resolve("retained-a/keep.txt")).toString(Charsets.UTF_8)).isEqualTo("保留现场")
    }

    @Test fun insufficientFreeSpaceStopsBeforeCreatingCopies() = fixture { context, root ->
        assertThrows(Exception::class.java) {
            AndroidDualDatabaseSourceProbe(context, minimumFreeBytes = Long.MAX_VALUE / 2).inspectDualSources()
        }
        assertThat(Files.list(root.resolve("no_backup/dual-db-probes-v1")).use { it.count() }).isEqualTo(0L)
    }

    @Test fun frozenCopiesValidateBothSourcesAndSuccessfulCleanupIsBounded() = fixture { context, root ->
        val before = sourceHashes(context)
        val result = AndroidDualDatabaseSourceProbe(context).inspectDualSources()
        assertThat(result!!.legacy.userVersion).isEqualTo(17)
        assertThat(result.current.userVersion).isEqualTo(2)
        assertThat(sourceHashes(context)).isEqualTo(before)
        assertThat(Files.list(root.resolve("no_backup/dual-db-probes-v1")).use { it.count() }).isEqualTo(0L)
    }

    @Test fun walOnlyCommittedBytesAreValidatedWithoutChangingSourceWal() = fixture { context, _ ->
        SQLiteDatabase.openDatabase(context.getDatabasePath(CURRENT_DATABASE_NAME).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertThat(writer.enableWriteAheadLogging()).isTrue()
            writer.execSQL("CREATE TABLE probe_wal_only(value TEXT)")
            writer.execSQL("INSERT INTO probe_wal_only VALUES('仅WAL中的合成数据')")
            val before = sourceHashes(context)
            val result = AndroidDualDatabaseSourceProbe(context).inspectDualSources()!!
            assertThat(result.current.files.map { it.relativePath }).contains("$CURRENT_DATABASE_NAME-wal")
            assertThat(sourceHashes(context)).isEqualTo(before)
        }
    }

    @Test fun unknownValidationContentIsPreservedAndCannotBecomeSuccess() = fixture { context, _ ->
        var unknown: Path? = null
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, path ->
            if (phase == SourceProbePhase.BEFORE_CLEANUP) {
                unknown = path.resolve("keep.txt")
                Files.write(unknown, "必须保留".toByteArray())
            }
        })
        assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
        assertThat(Files.readAllBytes(unknown!!).toString(Charsets.UTF_8)).isEqualTo("必须保留")
    }

    @Test fun recognizedSidecarNameWithoutOriginalIdentityIsStillPreserved() = fixture { context, root ->
        var unknown: Path? = null
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, path ->
            if (phase == SourceProbePhase.BEFORE_CLEANUP) {
                unknown = path.resolve("$CURRENT_DATABASE_NAME-shm")
                if (Files.exists(unknown)) Files.move(unknown, root.resolve("retained-probe-shm"))
                Files.write(unknown, "同名但不属于本操作".toByteArray(), StandardOpenOption.CREATE_NEW)
            }
        })
        assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
        assertThat(Files.readAllBytes(unknown!!).toString(Charsets.UTF_8)).isEqualTo("同名但不属于本操作")
    }

    @Test fun replacedValidationDirectoryIsPreservedWithoutRecursiveCleanup() = fixture { context, root ->
        var replacement: Path? = null
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, path ->
            if (phase == SourceProbePhase.BEFORE_SQLITE) {
                Files.move(path, root.resolve("retained-validation"))
                Files.createDirectory(path)
                replacement = path.resolve("keep.txt")
                Files.write(replacement, "未知内容".toByteArray())
            }
        })
        assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
        assertThat(Files.readAllBytes(replacement!!).toString(Charsets.UTF_8)).isEqualTo("未知内容")
        assertThat(Files.exists(root.resolve("retained-validation/$LEGACY_DATABASE_NAME"))).isTrue()
    }

    @Test fun sourceParentSymlinkReplacementNeverRedirectsReads() = fixture { context, root ->
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.write(outside.resolve(LEGACY_DATABASE_NAME), "外部内容".toByteArray())
        Files.write(outside.resolve(CURRENT_DATABASE_NAME), "外部内容".toByteArray())
        val original = context.getDatabasePath(CURRENT_DATABASE_NAME).parentFile!!.toPath()
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, _ ->
            if (phase == SourceProbePhase.SOURCE_SCANNED) {
                Files.move(original, root.resolve("retained-databases"))
                Files.createSymbolicLink(original, outside)
            }
        })
        try {
            assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
            assertThat(Files.readAllBytes(outside.resolve(CURRENT_DATABASE_NAME)).toString(Charsets.UTF_8)).isEqualTo("外部内容")
        } finally { Files.deleteIfExists(original) }
    }

    @Test fun sourceByteAbaIsRejectedByActualCopyHash() = fixture { context, _ ->
        val current = context.getDatabasePath(CURRENT_DATABASE_NAME).toPath()
        val original = Files.readAllBytes(current)
        val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, path ->
            if (path.fileName.toString() == CURRENT_DATABASE_NAME) {
                if (phase == SourceProbePhase.BEFORE_COPY) Files.write(current, changed)
                if (phase == SourceProbePhase.COPIED) Files.write(current, original)
            }
        })
        assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
        assertThat(Files.readAllBytes(current)).isEqualTo(original)
    }

    @Test fun growingSourceIsBoundedAndRetained() = fixture { context, _ ->
        val current = context.getDatabasePath(CURRENT_DATABASE_NAME).toPath()
        val beforeSize = Files.size(current)
        var grown = false
        val probe = AndroidDualDatabaseSourceProbe(context, hook = { phase, path ->
            if (phase == SourceProbePhase.COPY_CHUNK && path.fileName.toString() == CURRENT_DATABASE_NAME && !grown) {
                grown = true
                Files.write(current, ByteArray(128 * 1024), StandardOpenOption.APPEND)
            }
        })
        assertThrows(IllegalStateException::class.java) { probe.inspectDualSources() }
        assertThat(Files.size(current)).isEqualTo(beforeSize + 128 * 1024)
    }

    @Test fun declaredBudgetStopsBeforeCreatingValidationDirectory() = fixture { context, root ->
        assertThrows(IllegalStateException::class.java) {
            AndroidDualDatabaseSourceProbe(context, maxDatabaseBytes = 1).inspectDualSources()
        }
        assertThat(Files.exists(root.resolve("no_backup/dual-db-probes-v1"))).isFalse()
    }

    private fun fixture(body: (Context, Path) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createDirectory(context.filesDir.toPath().resolve("source-probe-audit-${UUID.randomUUID()}"))
        val isolated = object : ContextWrapper(context) {
            init { listOf("databases", "files", "no_backup").forEach { Files.createDirectory(root.resolve(it)) } }
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = root.toFile()
            override fun getFilesDir(): File = root.resolve("files").toFile()
            override fun getNoBackupFilesDir(): File = root.resolve("no_backup").toFile()
            override fun getDatabasePath(name: String): File = root.resolve("databases").resolve(name).toFile()
        }
        try {
            createFixture(isolated, 17, LEGACY_DATABASE_NAME)
            createFixture(isolated, 2, CURRENT_DATABASE_NAME)
            body(isolated, root)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun createFixture(context: Context, version: Int, name: String) {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("public-v$version.json").use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { database ->
            fixture.getValue("statements").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
            database.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)",
                arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content))
            database.version = version
        }
    }

    private fun sourceHashes(context: Context): Map<String, String> {
        val parent = context.getDatabasePath(CURRENT_DATABASE_NAME).parentFile!!.toPath()
        return listOf(LEGACY_DATABASE_NAME, CURRENT_DATABASE_NAME).flatMap { listOf(it, "$it-wal", "$it-journal") }
            .filter { Files.exists(parent.resolve(it)) }.associateWith {
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(parent.resolve(it)))
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
            }
    }
}
