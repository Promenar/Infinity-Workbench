package com.promenar.nexara.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AndroidTransactionalBackupPreferenceStoreTest {
    private lateinit var context: Context
    private lateinit var ledgerFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ledgerFile = File(context.noBackupFilesDir, "preference-test-${System.nanoTime()}.json")
        physicalNamespaces.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @After
    fun tearDown() {
        physicalNamespaces.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        ledgerFile.delete()
        File(ledgerFile.path + ".bak").delete()
        File(ledgerFile.path + ".new").delete()
    }

    @Test
    fun `snapshot preserves all SharedPreferences value types across six namespaces and filters denied keys`() = runBlocking {
        context.getSharedPreferences("nexara_provider", 0).edit()
            .putString("model", "m1")
            .putString("api_key", "must-not-leak")
            .commit()
        context.getSharedPreferences("nexara_settings", 0).edit()
            .putBoolean("haptic_enabled", true)
            .putInt("loop_limit", 9)
            .putFloat("default_temperature", 0.4f)
            .putStringSet("enabled_skills", setOf("z", "a"))
            .putInt("extra_providers_count", 3)
            .putString("extra_providers_ids", "provider-zero,provider-one")
            .putString("extra_provider_0_id", "provider-zero")
            .commit()
        context.getSharedPreferences("nexara_search", 0).edit().putInt("result_count", 7).commit()
        context.getSharedPreferences("rag_settings", 0).edit().putFloat("hybrid_alpha", 0.65f).commit()
        context.getSharedPreferences("nexara_prefs", 0).edit().putBoolean("has_shown_welcome", true).commit()
        context.getSharedPreferences("nexara_backup_settings", 0).edit()
            .putLong("last_backup_time", 42L)
            .putString("webdav_pass", "must-not-leak")
            .commit()

        val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)

        assertThat(snapshot.providerIds).containsExactly("default", "provider-zero", "provider-one", "extra_2")
        assertThat(snapshot.entries.associateBy { it.namespace to it.key })
            .containsAtLeastEntriesIn(mapOf(
                ("provider" to "model") to entry("provider", "model", PreferenceValueType.STRING, "m1"),
                ("settings" to "haptic_enabled") to entry("settings", "haptic_enabled", PreferenceValueType.BOOLEAN, "true"),
                ("settings" to "loop_limit") to entry("settings", "loop_limit", PreferenceValueType.INT, "9"),
                ("settings" to "default_temperature") to entry("settings", "default_temperature", PreferenceValueType.FLOAT, "0.4"),
                ("settings" to "enabled_skills") to entry("settings", "enabled_skills", PreferenceValueType.STRING_SET, "[\"a\",\"z\"]"),
                ("search" to "result_count") to entry("search", "result_count", PreferenceValueType.INT, "7"),
                ("rag" to "hybrid_alpha") to entry("rag", "hybrid_alpha", PreferenceValueType.FLOAT, "0.65"),
                ("ui" to "has_shown_welcome") to entry("ui", "has_shown_welcome", PreferenceValueType.BOOLEAN, "true"),
                ("backup" to "last_backup_time") to entry("backup", "last_backup_time", PreferenceValueType.LONG, "42"),
            ))
        assertThat(snapshot.entries.map { it.key }).doesNotContain("api_key")
        assertThat(snapshot.entries.map { it.key }).doesNotContain("webdav_pass")
    }

    @Test
    fun `commit removes absent allowed keys preserves denied keys and is idempotent after recreation`() = runBlocking {
        val settings = context.getSharedPreferences("nexara_settings", 0)
        settings.edit()
            .putString("language", "en")
            .putInt("loop_limit", 3)
            .putString("api_key", "legacy-secret")
            .putString("unregistered_internal", "keep")
            .commit()
        val first = store()
        val before = first.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = BackupPreferenceSnapshot(
            entries = listOf(
                entry("settings", "language", PreferenceValueType.STRING, "zh"),
                entry("settings", "haptic_enabled", PreferenceValueType.BOOLEAN, "true"),
                entry("search", "result_count", PreferenceValueType.INT, "11"),
                entry("rag", "memory_threshold", PreferenceValueType.FLOAT, "0.7"),
                entry("ui", "has_shown_welcome", PreferenceValueType.BOOLEAN, "true"),
                entry("backup", "last_backup_time", PreferenceValueType.LONG, "99"),
            ),
            providerIds = setOf("default"),
        )

        first.prepare("tx-commit", before, after)
        store().commitPrepared("tx-commit")
        store().commitPrepared("tx-commit")

        assertThat(settings.getString("language", null)).isEqualTo("zh")
        assertThat(settings.getBoolean("haptic_enabled", false)).isTrue()
        assertThat(settings.contains("loop_limit")).isFalse()
        assertThat(settings.getString("api_key", null)).isEqualTo("legacy-secret")
        assertThat(settings.getString("unregistered_internal", null)).isEqualTo("keep")
        assertThat(context.getSharedPreferences("nexara_search", 0).getInt("result_count", 0)).isEqualTo(11)
        assertThat(context.getSharedPreferences("rag_settings", 0).getFloat("memory_threshold", 0f)).isEqualTo(0.7f)
        assertThat(context.getSharedPreferences("nexara_prefs", 0).getBoolean("has_shown_welcome", false)).isTrue()
        assertThat(context.getSharedPreferences("nexara_backup_settings", 0).getLong("last_backup_time", 0)).isEqualTo(99)
    }

    @Test
    fun `partial commit can be retried after recreation`() = runBlocking {
        seedBeforeState()
        val initial = store()
        val before = initial.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = changedSnapshot()
        initial.prepare("tx-partial-commit", before, after)
        val failing = store { namespace, direction ->
            if (namespace == "settings" && direction == PreferenceApplyDirection.AFTER) {
                throw SimulatedPreferenceProcessDeath()
            }
        }

        assertThrows<SimulatedPreferenceProcessDeath> { failing.commitPrepared("tx-partial-commit") }
        store().commitPrepared("tx-partial-commit")

        assertThat(context.getSharedPreferences("nexara_settings", 0).getString("language", null)).isEqualTo("zh")
        assertThat(context.getSharedPreferences("nexara_search", 0).getInt("result_count", 0)).isEqualTo(10)
    }

    @Test
    fun `partial commit can be rolled back after recreation`() = runBlocking {
        seedBeforeState()
        val initial = store()
        val before = initial.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        initial.prepare("tx-partial-rollback", before, changedSnapshot())
        val failing = store { namespace, direction ->
            if (namespace == "settings" && direction == PreferenceApplyDirection.AFTER) {
                throw SimulatedPreferenceProcessDeath()
            }
        }
        assertThrows<SimulatedPreferenceProcessDeath> { failing.commitPrepared("tx-partial-rollback") }

        store().rollbackPrepared("tx-partial-rollback")

        assertThat(context.getSharedPreferences("nexara_settings", 0).getString("language", null)).isEqualTo("en")
        assertThat(context.getSharedPreferences("nexara_search", 0).getInt("result_count", 0)).isEqualTo(5)
    }

    @Test
    fun `finalized receipt survives recreation and distinguishes completed direction from missing state`() = runBlocking {
        seedBeforeState()
        val first = store()
        val before = first.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        first.prepare("tx-final", before, changedSnapshot())
        first.commitPrepared("tx-final")
        first.finalizePrepared("tx-final")

        store().commitPrepared("tx-final")
        store().finalizePrepared("tx-final")
        assertFails { store().rollbackPrepared("tx-final") }
        store().rollbackPrepared("never-prepared")
        store().finalizePrepared("never-prepared")
        assertFails { store().commitPrepared("never-prepared") }
    }

    @Test
    fun `preflight validates typed values and provider ids before creating ledger`() = runBlocking {
        val adapter = store()
        val before = BackupPreferenceSnapshot(emptyList(), setOf("default"))
        val malformed = BackupPreferenceSnapshot(
            entries = listOf(entry("settings", "loop_limit", PreferenceValueType.STRING, "not-an-int")),
            providerIds = setOf("default"),
        )

        assertFails { adapter.preflightRestore("tx-malformed", before, malformed) }

        assertThat(ledgerFile.exists()).isFalse()
    }

    @Test
    fun `preflight rejects ledger capacity before writing ledger`() = runBlocking {
        val snapshot = BackupPreferenceSnapshot(emptyList(), setOf("default"))

        assertFails { store(maxLedgerBytes = 32).preflightRestore("tx-capacity", snapshot, snapshot) }

        assertThat(ledgerFile.exists()).isFalse()
    }

    @Test
    fun `prepare is idempotent only for identical canonical snapshots and validates provider ids`() = runBlocking {
        seedBeforeState()
        val adapter = store()
        val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = changedSnapshot()

        adapter.prepare("tx-prepare", before, after)
        store().prepare("tx-prepare", before, after)
        assertFails { store().prepare("tx-prepare", before, after.copy(entries = emptyList())) }
        assertFails { store().prepare("tx-bad-provider", before, after.copy(providerIds = setOf("forged"))) }
    }

    private fun store(
        maxLedgerBytes: Long = 34L * 1024 * 1024,
        hook: (String, PreferenceApplyDirection) -> Unit = { _, _ -> },
    ) = AndroidTransactionalBackupPreferenceStore(context, ledgerFile, hook, maxLedgerBytes)

    private fun seedBeforeState() {
        context.getSharedPreferences("nexara_settings", 0).edit().putString("language", "en").commit()
        context.getSharedPreferences("nexara_search", 0).edit().putInt("result_count", 5).commit()
    }

    private fun changedSnapshot() = BackupPreferenceSnapshot(
        entries = listOf(
            entry("settings", "language", PreferenceValueType.STRING, "zh"),
            entry("search", "result_count", PreferenceValueType.INT, "10"),
        ),
        providerIds = setOf("default"),
    )

    private fun entry(namespace: String, key: String, type: PreferenceValueType, value: String) =
        BackupPreferenceEntry(namespace, key, value, type)

    private suspend inline fun <reified T : Throwable> assertThrows(noinline block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertThat(thrown).isInstanceOf(T::class.java)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    private class SimulatedPreferenceProcessDeath : Error()

    private companion object {
        val physicalNamespaces = listOf(
            "nexara_provider",
            "nexara_settings",
            "nexara_search",
            "rag_settings",
            "nexara_prefs",
            "nexara_backup_settings",
        )
    }
}
