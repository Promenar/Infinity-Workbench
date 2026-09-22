package com.promenar.nexara.data.local.db.recovery

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** 验证真正迁移过的未记账working副本不会在重启时被复用或删除。 */
class AndroidDualDatabaseRecoveryInterruptionTest {
    @Test
    fun migrationInterruptionsRestartFromFreshImmutableSnapshotAttempt() {
        listOf(
            DualDatabaseRecoveryFaultPoint.AFTER_LEGACY_MIGRATION,
            DualDatabaseRecoveryFaultPoint.BEFORE_STAGED_JOURNAL,
        ).forEach(::verifyInterruption)
    }

    @Test
    fun renameBeforeDirectoryFsyncIsReverifiedAndCompletedOnRestart() {
        verifyInterruption(DualDatabaseRecoveryFaultPoint.AFTER_RENAME_BEFORE_DIRECTORY_FSYNC)
    }

    @Test
    fun completedTreeArchiveWithoutJournalReceiptIsRetainedAndFreshAttemptCanResumeAfterDiagnosticsChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Files.createDirectory(
            instrumentation.targetContext.filesDir.toPath().resolve("dual-db-tree-interruption-${UUID.randomUUID()}"),
        )
        val context = IsolatedContext(instrumentation.targetContext, root.toFile())
        try {
            createPublishedFixture(context, 17, context.getDatabasePath(LEGACY_DATABASE_NAME))
            createPublishedFixture(context, 2, context.getDatabasePath(CURRENT_DATABASE_NAME))
            context.filesDir.resolve("diagnostics.log").writeText("before")
            val journal = MemoryJournal()
            val snapshotStore = RecoverySnapshotStore(
                setOf(context.getDatabasePath(CURRENT_DATABASE_NAME).parentFile!!.toPath(), context.filesDir.toPath()),
                10_000,
                64L * 1024 * 1024,
                context.dataDir.toPath(),
            )
            val probe = AndroidDualDatabaseSourceProbe(context)
            val interrupted = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(),
                probe,
                journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, snapshotStore) {
                    if (it == DualDatabaseRecoveryFaultPoint.AFTER_TREE_ARCHIVE_BEFORE_JOURNAL) {
                        throw SimulatedProcessDeath()
                    }
                },
            )
            val offer = interrupted.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired

            assertThat(interrupted.recover(offer.offer.offerToken))
                .isInstanceOf(DualDatabaseBootstrapResult.Blocked::class.java)
            val interruptedRecord = checkNotNull(journal.value)
            assertThat(interruptedRecord.stage).isEqualTo(DualDatabaseRecoveryStage.STAGED)
            assertThat(interruptedRecord.treeArchiveAttempts).hasSize(1)
            assertThat(interruptedRecord.treePublications).isEmpty()
            val orphanAttempt = interruptedRecord.treeArchiveAttempts.single().snapshotTransactionId
            val archiveRoot = context.noBackupFilesDir.toPath().resolve("dual-db-tree-archives-v1")
            assertThat(archiveRoot.resolve(orphanAttempt).toFile().isDirectory).isTrue()

            context.filesDir.resolve("diagnostics.log").appendText("-after-crash")
            val resumed = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(),
                probe,
                journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, snapshotStore),
            ).inspectOrResume()

            assertThat(resumed).isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)
            val completed = checkNotNull(journal.value)
            assertThat(completed.treeArchiveAttempts.map { it.snapshotTransactionId }).contains(orphanAttempt)
            assertThat(completed.treePublications.map { it.snapshotTransactionId }).doesNotContain(orphanAttempt)
            assertThat(archiveRoot.resolve(orphanAttempt).toFile().isDirectory).isTrue()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun verifyInterruption(point: DualDatabaseRecoveryFaultPoint) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Files.createDirectory(
            instrumentation.targetContext.filesDir.toPath().resolve("dual-db-interruption-${UUID.randomUUID()}"),
        )
        val context = IsolatedContext(instrumentation.targetContext, root.toFile())
        try {
            createPublishedFixture(context, 17, context.getDatabasePath(LEGACY_DATABASE_NAME))
            createPublishedFixture(context, 2, context.getDatabasePath(CURRENT_DATABASE_NAME))
            val journal = MemoryJournal()
            val snapshotStore = RecoverySnapshotStore(
                setOf(context.getDatabasePath(CURRENT_DATABASE_NAME).parentFile!!.toPath(), context.filesDir.toPath()),
                10_000,
                64L * 1024 * 1024,
                context.dataDir.toPath(),
            )
            val probe = AndroidDualDatabaseSourceProbe(context)
            val interruptedExecutor = AndroidDualDatabaseRecoveryExecutor(context, journal, snapshotStore) {
                if (it == point) throw SimulatedProcessDeath()
            }
            val interrupted = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(), probe, journal, interruptedExecutor,
            )
            val offer = interrupted.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired

            assertThat(interrupted.recover(offer.offer.offerToken))
                .isInstanceOf(DualDatabaseBootstrapResult.Blocked::class.java)
            val interruptedRecord = checkNotNull(journal.value)
            val migrationInterruption = point != DualDatabaseRecoveryFaultPoint.AFTER_RENAME_BEFORE_DIRECTORY_FSYNC
            assertThat(interruptedRecord.stage).isEqualTo(
                if (migrationInterruption) DualDatabaseRecoveryStage.SNAPSHOTTED
                else DualDatabaseRecoveryStage.DB_PUBLISHING,
            )
            if (migrationInterruption) assertThat(interruptedRecord.workingAttemptId).isNull()
            else assertThat(interruptedRecord.databaseMoves.any { !it.completed }).isTrue()
            val transactionRoot = context.noBackupFilesDir.toPath()
                .resolve("dual-db-working-v1").resolve(interruptedRecord.transactionId)
            val retainedAttempts = checkNotNull(transactionRoot.toFile().listFiles()).filter { it.isDirectory }
            assertThat(retainedAttempts).hasSize(1)

            val resumed = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(),
                probe,
                journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, snapshotStore),
            ).inspectOrResume()

            assertThat(resumed).isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)
            val completed = checkNotNull(journal.value)
            assertThat(completed.stage).isEqualTo(DualDatabaseRecoveryStage.COMPLETE)
            if (migrationInterruption) {
                assertThat(completed.workingAttemptId).isNotEqualTo(retainedAttempts.single().name)
            } else {
                assertThat(completed.workingAttemptId).isEqualTo(retainedAttempts.single().name)
            }
            assertThat(checkNotNull(transactionRoot.toFile().listFiles()).filter { it.isDirectory })
                .hasSize(if (migrationInterruption) 2 else 1)
            assertThat(retainedAttempts.single().exists()).isTrue()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createPublishedFixture(context: Context, version: Int, path: File) {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("public-v$version.json").use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
        }
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            fixture.getValue("statements").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
            database.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)",
                arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content),
            )
            database.version = version
        }
    }

    private class MemoryJournal : DualDatabaseRecoveryJournal {
        var value: DualDatabaseRecoveryRecord? = null
        override fun read(): DualDatabaseRecoveryRecord? = value
        override fun write(record: DualDatabaseRecoveryRecord) { value = record }
    }

    private class SimulatedProcessDeath : RuntimeException("模拟进程在STAGED journal落盘前中断")

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
