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

        assertThat(onCreate).contains("backupRuntime = createBackupRuntime()")
        assertThat(onCreate).contains("startBackupRecovery()")
        assertThat(onCreate).doesNotContain("recoverBeforeWriters()")
        writerMarkers.forEach { marker -> assertThat(onCreate).doesNotContain(marker) }
        assertThat(source).doesNotContain("runBlocking")
    }

    @Test
    fun `all business writers live behind one transactional ready-only initializer`() {
        val preflight = functionBody("private fun prepareStartupWriters(): PreparedStartupWriters")
        val registrations = functionBody(
            "private fun startupWriterRegistrations("
        )
        preflightMarkers.forEach { marker -> assertThat(preflight).contains(marker) }
        registrationMarkers.forEach { marker -> assertThat(registrations).contains(marker) }

        val recovery = functionBody("private fun startBackupRecovery()")
        assertThat(recovery).contains("backupRuntime.recoverBeforeWriters()")
        assertThat(recovery).contains("BackupStartupState.Ready")
        assertThat(recovery).contains("initializeAfterRecoveryOnce()")
        val initialize = recovery.indexOf("initializeAfterRecoveryOnce()")
        assertThat(recovery.substring(initialize)).contains("BackupStartupState.Ready")

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
            "workSpaceDir.mkdirs()",
            "ProviderManager.init(this, secretStore)",
            "getSharedPreferences(\"nexara_settings\", MODE_PRIVATE)",
        )
        val registrationMarkers = listOf(
            "ProcessLifecycleOwner.get().lifecycle.addObserver",
            "prefs.registerOnSharedPreferenceChangeListener(providerListener)",
            "prepared.settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)",
            "prepared.providerManager.configurationChanges",
            "prepared.inferenceEngine.loadModel",
            "prepared.vectorizationQueue.resumeInterruptedTasks()",
        )
    }
}
