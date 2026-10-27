package com.promenar.nexara.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionHeaderBackupWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("nexara_settings", 0)
    private val ledger = File(context.noBackupFilesDir, "session-header-backup-test.json")
    private fun store() = AndroidTransactionalBackupPreferenceStore(context, ledger)
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() {
        prefs.edit().clear().commit()
        listOf(ledger, File(ledger.path + ".bak"), File(ledger.path + ".new")).forEach { it.delete() }
    }

    @Test fun `三类会话头设置导出恢复并清理恢复前残留键而不备份安装盐`() = runBlocking {
        prefs.edit().putBoolean(ENABLED, true).putString(NAME, "x-session")
            .putString(MODE, "OFF").commit()
        val salt = File(context.noBackupFilesDir, "session_headers/installation-salt")
        salt.parentFile!!.mkdirs()
        val oldSalt = salt.takeIf { it.exists() }?.readBytes()
        salt.writeText("synthetic-installation-salt")
        try {
            val backup = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            assertThat(backup.entries.map { it.key }).containsAtLeast(ENABLED, NAME, MODE)
            assertThat(backup.entries.none { it.key.contains("salt") || it.value.contains("synthetic-installation-salt") }).isTrue()
            prefs.edit().putBoolean("session_header_provider_old_enabled", true)
                .putString("session_header_provider_old_name", "x-old").putString("session_header_model_old::model", "ON")
                .putBoolean(ENABLED, false).commit()
            val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            store().prepare("session-header-restore", before, backup)
            store().commitPrepared("session-header-restore")
            assertThat(prefs.getBoolean(ENABLED, false)).isTrue()
            assertThat(prefs.getString(NAME, null)).isEqualTo("x-session")
            assertThat(prefs.getString(MODE, null)).isEqualTo("OFF")
            assertThat(prefs.all.keys.none { it.contains("old") }).isTrue()
            assertThat(salt.readText()).isEqualTo("synthetic-installation-salt")
            store().rollbackPrepared("session-header-restore")
            assertThat(prefs.getString("session_header_model_old::model", null)).isEqualTo("ON")
        } finally { if (oldSalt == null) salt.delete() else salt.writeBytes(oldSalt) }
    }

    @Test fun `恢复旧备份移除全部会话头键`() = runBlocking {
        prefs.edit().putBoolean(ENABLED, true).putString(NAME, "x-session").putString(MODE, "ON").commit()
        val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        store().prepare("old-backup", before, BackupPreferenceSnapshot(emptyList(), setOf("default")))
        store().commitPrepared("old-backup")
        assertThat(prefs.all.keys.filter { it.startsWith("session_header_") }).isEmpty()
    }

    @Test fun `非法布尔字符串与枚举值在导出时拒绝`() = runBlocking {
        val invalid = listOf<Pair<String, Any>>(
            ENABLED to "true", ENABLED to 1, NAME to true, MODE to true, MODE to "INVALID", MODE to "on",
        )
        invalid.forEach { (key, value) ->
            prefs.edit().clear().apply {
                when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) }
            }.commit()
            assertThat(runCatching { store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES) }.exceptionOrNull())
                .isInstanceOf(BackupValidationException::class.java)
        }
    }

    @Test fun `非法类型或枚举备份在写入前拒绝且合法三态均可恢复`() = runBlocking {
        val before = BackupPreferenceSnapshot(emptyList(), setOf("default"))
        val invalid = listOf(
            BackupPreferenceEntry("settings", ENABLED, "true", PreferenceValueType.STRING),
            BackupPreferenceEntry("settings", ENABLED, "TRUE", PreferenceValueType.BOOLEAN),
            BackupPreferenceEntry("settings", NAME, "true", PreferenceValueType.BOOLEAN),
            BackupPreferenceEntry("settings", MODE, "INVALID", PreferenceValueType.STRING),
        )
        invalid.forEachIndexed { i, entry ->
            assertThat(runCatching { store().prepare("invalid-$i", before, before.copy(entries = listOf(entry))) }.exceptionOrNull())
                .isInstanceOf(BackupValidationException::class.java)
        }
        listOf("INHERIT", "ON", "OFF").forEach { mode ->
            store().prepare("valid-$mode", before, before.copy(entries = listOf(BackupPreferenceEntry("settings", MODE, mode, PreferenceValueType.STRING))))
            store().commitPrepared("valid-$mode")
            assertThat(prefs.getString(MODE, null)).isEqualTo(mode)
        }
    }

    @Test fun `标点和token字样的稳定ID仍可按键类型备份`() = runBlocking {
        val enabled = "session_header_provider_api-token-gateway_enabled"
        val mode = "session_header_model_api-token-gateway::model/name"
        prefs.edit().putBoolean(enabled, true).putString(mode, "INHERIT").commit()
        assertThat(store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES).entries.map { it.key })
            .containsAtLeast(enabled, mode)
        assertThat(BackupPreferencePolicy.isAllowed("settings", "session_header_provider__enabled")).isFalse()
        assertThat(BackupPreferencePolicy.isAllowed("settings", "session_header_model_")).isFalse()
        assertThat(BackupPreferencePolicy.isAllowed("provider", enabled)).isFalse()
    }

    companion object {
        const val ENABLED = "session_header_provider_gateway_enabled"
        const val NAME = "session_header_provider_gateway_name"
        const val MODE = "session_header_model_gateway::model"
    }
}
