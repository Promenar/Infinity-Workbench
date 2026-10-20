package com.promenar.nexara.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.tailnet.MemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 启动期待恢复事务在确定性失败后的去向：已完整回滚时解除锁定并保留原数据，补偿未完成时继续 fail closed。
 * 偏好账本使用真实实现，因为“同 txId 记录不一致”的永久锁定只在真实账本上出现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BackupRestoreFailureRecoveryTest {
    private lateinit var context: Context
    private lateinit var db: NexaraDatabase
    private lateinit var testBase: Path
    private lateinit var sourceBase: Path
    private lateinit var restoreParent: Path
    private lateinit var skills: Path
    private lateinit var ledgerFile: File
    private lateinit var secretMetadataName: String
    private val liveSecrets = MemorySecretStore()
    private val stagingSecrets = MemorySecretStore()
    private val codec = DefaultBackupPackageCodec()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PHYSICAL_NAMESPACES.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        testBase = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".nexara-restore-failure-test")
        sourceBase = Files.createDirectory(testBase.resolve("source"))
        Files.createDirectory(sourceBase.resolve("root-1"))
        restoreParent = Files.createDirectory(testBase.resolve("restore"))
        skills = Files.createDirectory(sourceBase.resolve("agent_skills"))
        ledgerFile = File(context.noBackupFilesDir, "preference-ledger-${System.nanoTime()}.json")
        secretMetadataName = "backup-secret-metadata-${System.nanoTime()}"
    }

    @After
    fun tearDown() {
        db.close()
        testBase.toFile().deleteRecursively()
        listOf("", ".bak", ".new").forEach { File(ledgerFile.path + it).delete() }
        PHYSICAL_NAMESPACES.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        context.getSharedPreferences(secretMetadataName, 0).edit().clear().commit()
    }

    @Test
    fun `确定性失败已回滚后再次启动解除锁定且数据保持原状`(): Unit = runBlocking {
        val bytes = backupThenDiverge()
        val pending = MemoryPendingStore(TX_ID, bytes)

        // 旧版本留下的现场：数据源已回滚并删除 journal，pending 仍为 STAGED，偏好账本记为 FINALIZED。
        assertFails { dataSource(securityFailingSkillOps()).restore(codec.decode(bytes, null), TX_ID) }
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        assertThat(pending.isPresent).isTrue()

        val runtime = BackupRuntime(dataSource(), pending, codec)

        assertThat(runtime.recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(pending.isPresent).isFalse()
        assertThat(runtime.restoreNotApplied.value).isTrue()
        assertThat(runtime.restoreResult.value).isNull()
        assertDivergedLiveStateKept()
    }

    @Test
    fun `启动期恢复确定性失败时当次即释放待恢复记录且下次启动不再重放`(): Unit = runBlocking {
        val bytes = backupThenDiverge()
        val pending = MemoryPendingStore(TX_ID, bytes)

        val first = BackupRuntime(dataSource(securityFailingSkillOps()), pending, codec)
        assertThat(first.recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(first.restoreNotApplied.value).isTrue()
        assertThat(pending.isPresent).isFalse()
        assertDivergedLiveStateKept()

        val second = BackupRuntime(dataSource(), pending, codec)
        assertThat(second.recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(second.restoreNotApplied.value).isFalse()
        assertDivergedLiveStateKept()
    }

    @Test
    fun `回滚补偿未完成时保持锁定且补偿完成后才释放待恢复记录`(): Unit = runBlocking {
        val bytes = backupThenDiverge()
        val pending = MemoryPendingStore(TX_ID, bytes)
        val failingRollback = AndroidTransactionalBackupPreferenceStore(context, ledgerFile, { _, direction ->
            if (direction == PreferenceApplyDirection.BEFORE) throw IllegalStateException("注入偏好回滚失败")
        })

        val blocked = BackupRuntime(dataSource(securityFailingSkillOps(), failingRollback), pending, codec)
        assertThat(blocked.recoverBeforeWriters()).isEqualTo(BackupStartupState.Blocked)
        assertThat(blocked.restoreNotApplied.value).isFalse()
        assertThat(pending.isPresent).isTrue()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

        val recovered = BackupRuntime(dataSource(), pending, codec)
        assertThat(recovered.recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(recovered.restoreNotApplied.value).isTrue()
        assertThat(pending.isPresent).isFalse()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        assertDivergedLiveStateKept()
    }

    @Test
    fun `完成回执存在时失败恢复不被视为未应用`(): Unit = runBlocking {
        val bytes = backupThenDiverge()
        dataSource().restore(codec.decode(bytes, null), TX_ID)

        assertThat(dataSource().isRestoreSettledWithoutApplying(TX_ID)).isFalse()
        assertThat(dataSource().isRestoreSettledWithoutApplying("other-tx")).isTrue()
    }

    /** 备份时为 agent-backup/new/kg_max_seeds=8，之后现场改为 agent-live/old/kg_max_seeds=3。 */
    private suspend fun backupThenDiverge(): ByteArray {
        db.agentDao().insert(AgentEntity("agent-backup", "Backup", createdAt = 1L))
        Files.write(skills.resolve("new"), "新".toByteArray())
        ragPreferences().edit().putInt("kg_max_seeds", 8).commit()
        val snapshot = dataSource().snapshot(CANONICAL_CONTENT)
        val bytes = try {
            codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        } finally {
            snapshot.wipe()
        }
        db.clearAllTables()
        db.agentDao().insert(AgentEntity("agent-live", "Live", createdAt = 2L))
        Files.delete(skills.resolve("new"))
        Files.write(skills.resolve("old"), "旧".toByteArray())
        ragPreferences().edit().putInt("kg_max_seeds", 3).commit()
        return bytes
    }

    private suspend fun assertDivergedLiveStateKept() {
        assertThat(db.agentDao().getById("agent-live")).isNotNull()
        assertThat(db.agentDao().getById("agent-backup")).isNull()
        assertThat(Files.readAllBytes(skills.resolve("old")).toString(Charsets.UTF_8)).isEqualTo("旧")
        assertThat(Files.exists(skills.resolve("new"))).isFalse()
        assertThat(ragPreferences().getInt("kg_max_seeds", -1)).isEqualTo(3)
    }

    private fun ragPreferences() = context.getSharedPreferences("rag_settings", 0)

    /** 复现 Android 上技能暂存目录写入抛 SecurityException 的确定性失败。 */
    private fun securityFailingSkillOps() = object : RestoreFileOperations by TestRestoreFileOperations() {
        override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
            if (root.fileName.toString().contains("skills-preparing")) throw SecurityException("注入技能暂存拒绝")
            TestRestoreFileOperations().writeNew(root, relative, bytes)
        }
    }

    private fun dataSource(
        fileOperations: RestoreFileOperations = TestRestoreFileOperations(),
        preferences: TransactionalBackupPreferenceStore = AndroidTransactionalBackupPreferenceStore(context, ledgerFile),
    ) = RoomBackupDataSource(
        database = db,
        preferences = preferences,
        secrets = AndroidTransactionalBackupSecretStore(
            liveSecrets,
            stagingSecrets,
            context.getSharedPreferences(secretMetadataName, 0),
        ),
        trustedSourceBases = setOf(sourceBase),
        trustedRestoreBase = restoreParent,
        appVersion = "test",
        journalAuthenticator = TestRestoreJournalAuthenticator,
        restoreFileOperations = fileOperations,
        userSkillsRoot = skills,
    )

    private fun assertFails(block: suspend () -> Unit) = runBlocking {
        val failure = runCatching { block() }.exceptionOrNull()
        assertThat(failure).isNotNull()
    }

    private class MemoryPendingStore(private val txId: String, private val bytes: ByteArray) : PendingRestoreStore {
        var isPresent = true
        override fun begin(expectedTxId: String) = error("unused")
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?) = error("unused")
        override fun authorize(expectedTxId: String) = error("unused")
        override fun read(): PendingRestorePayload? = if (!isPresent) null else PendingRestorePayload(
            PendingRestoreMetadata(txId, MessageDigest.getInstance("SHA-256").digest(bytes), PendingRestorePhase.STAGED),
            bytes.copyOf(),
            null,
        )
        override fun clear(expectedTxId: String) {
            check(expectedTxId == txId)
            isPresent = false
        }
        override fun cancel(expectedTxId: String) = error("unused")
    }

    private companion object {
        const val TX_ID = "123e4567-e89b-12d3-a456-426614174099"
        val CANONICAL_CONTENT = setOf(
            BackupContent.DATABASE,
            BackupContent.PREFERENCES,
            BackupContent.FILES,
            BackupContent.SECRETS,
        )
        val PHYSICAL_NAMESPACES = listOf(
            "nexara_provider", "nexara_settings", "nexara_search", "rag_settings", "nexara_prefs", "nexara_backup_settings",
        )
    }
}
