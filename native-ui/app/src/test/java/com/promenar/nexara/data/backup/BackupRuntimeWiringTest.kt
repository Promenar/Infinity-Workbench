package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupRuntimeWiringTest {
    private val source by lazy {
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/NexaraApplication.kt"))
            .toString(Charsets.UTF_8)
    }

    @Test
    fun `application onCreate starts recovery asynchronously without business writers`() {
        val onCreate = functionBody("override fun onCreate()")

        assertThat(onCreate).doesNotContain("createBackupRuntime()")
        assertThat(onCreate).contains("startBackupRecovery()")
        assertThat(onCreate).doesNotContain("recoverBeforeWriters()")
        writerMarkers.forEach { marker -> assertThat(onCreate).doesNotContain(marker) }
        assertThat(source).doesNotContain("runBlocking")
    }

    @Test
    fun `all business writers live behind one transactional ready-only initializer`() {
        val preflight = functionBody("private fun prepareAndMigrateStartupWriters(): PreparedStartupWriters")
        val registrations = functionBody(
            "private fun startupWriterRegistrations("
        )
        preflightMarkers.forEach { marker -> assertThat(preflight).contains(marker) }
        assertThat(preflight.indexOf("getSharedPreferences(\"nexara_settings\", MODE_PRIVATE)"))
            .isLessThan(preflight.indexOf("StartupMigration.WORKSPACE_DIRECTORY"))
        assertThat(preflight.indexOf("StartupMigration.WORKSPACE_DIRECTORY"))
            .isLessThan(preflight.indexOf("StartupMigration.PROVIDER_MIGRATION_AND_INIT"))
        registrationMarkers.forEach { marker -> assertThat(registrations).contains(marker) }

        val recovery = functionBody("private fun startBackupRecovery()")
        assertThat(recovery).contains("withContext(Dispatchers.IO)")
        assertThat(recovery).contains("backupRuntime = createBackupRuntime()")
        assertThat(recovery).contains("backupRuntime.recoverBeforeWriters()")
        assertThat(recovery).contains("catch (error: Exception)")
        assertThat(recovery).contains("_startupState.value = BackupStartupState.Blocked")
        assertThat(recovery).contains("initializeAfterRecoveryOnce()")
        // 各恢复步骤的先后与失败隔离由 PostBackupStartupRecoverySequence 固定，这里只核对接线顺序。
        val backup = recovery.indexOf("StartupStep.BACKUP_RESTORE")
        val sequence = recovery.indexOf("PostBackupStartupRecoverySequence(")
        val journal = recovery.indexOf("recoverWorkspaceJournal = { recoverStartupWorkspaces() }")
        val ledger = recovery.indexOf("toolExecutionLedger.recoverInterruptedRunning")
        val initialize = recovery.indexOf("initializeAfterRecoveryOnce()")
        val vector = recovery.indexOf("vectorizationQueue.resumeInterruptedTasks()")
        val run = recovery.indexOf(".runOrThrow()")
        val ready = recovery.indexOf("_startupState.value = BackupStartupState.Ready")
        assertThat(backup).isAtLeast(0)
        assertThat(backup).isLessThan(sequence)
        assertThat(sequence).isLessThan(journal)
        assertThat(journal).isLessThan(ledger)
        assertThat(ledger).isLessThan(initialize)
        assertThat(initialize).isLessThan(vector)
        assertThat(vector).isLessThan(run)
        assertThat(run).isLessThan(ready)

        // 旧根接管先于 journal 与 tombstone，在同一工作区恢复范围内完成。
        val workspaces = functionBody("private suspend fun recoverStartupWorkspaces()")
        assertThat(workspaces).contains("LegacyWorkspaceRootAdoptionCoordinator(")
        assertThat(workspaces).contains("workspaceMutationRecoveryCoordinator")
        assertThat(workspaces).contains(").recover()")

        val once = functionBody("private fun initializeAfterRecoveryOnce()")
        assertThat(once).contains("writersInitialized")
        assertThat(once).contains("createStartupWriterTransaction().commit()")
        assertThat(once.indexOf("createStartupWriterTransaction().commit()"))
            .isLessThan(once.indexOf("writersInitialized = true"))
    }

    @Test
    fun `runtime remains private and production factory uses secure datasource`() {
        assertThat(source).contains("private lateinit var backupRuntime: BackupRuntime")
        assertThat(source).contains("BackupRuntime.createAndroid(this, database, secretStore)")
        assertThat(source.indexOf("withContext(Dispatchers.IO)")).isLessThan(
            source.indexOf("backupRuntime = createBackupRuntime()"),
        )
    }

    @Test
    fun `failed runtime creation is retryable and concurrent retries share the active job gate`() {
        val recovery = functionBody("private fun startBackupRecovery()")

        assertThat(recovery).contains("startupRecoveryJob?.isActive == true")
        assertThat(recovery).contains("if (!::backupRuntime.isInitialized) backupRuntime = createBackupRuntime()")
        assertThat(recovery.indexOf("catch (error: Exception)")).isLessThan(
            recovery.lastIndexOf("_startupState.value = BackupStartupState.Blocked"),
        )
        assertThat(recovery).doesNotContain("backupRuntime = null")
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }

    private companion object {
        val writerMarkers = listOf(
            "ProviderManager.init(this, secretStore)",
            "ProcessLifecycleOwner.get().lifecycle.addObserver",
            "prefs.registerOnSharedPreferenceChangeListener(providerListener)",
            "prepared.settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)",
        )
        val preflightMarkers = listOf(
            "backupRuntime.requireWriterGate()",
            "getSharedPreferences(\"nexara_settings\", MODE_PRIVATE)",
            "StartupMigration.WORKSPACE_DIRECTORY",
            "workSpaceDir.mkdirs()",
            "StartupMigration.PROVIDER_MIGRATION_AND_INIT",
            "ProviderManager.init(this, secretStore)",
        )
        val registrationMarkers = listOf(
            "ProcessLifecycleOwner.get().lifecycle.addObserver",
            "prefs.registerOnSharedPreferenceChangeListener(providerListener)",
            "prepared.settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)",
            "prepared.providerManager.configurationChanges",
            "checkNotNull(prepared.inferenceEngine)",
            ".loadModel(SlotType.MAIN, modelPath)",
        )
    }
}
