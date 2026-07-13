package com.promenar.nexara.data.backup

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.repository.BackupRepository
import com.promenar.nexara.data.security.SecretCatalog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 由 host 脚本的四次独立 am instrument 调用驱动，覆盖：
 * 加密 includeKeys 导出 -> pending stage/authorize -> relay 杀进程恢复 -> 冷启不重放。
 *
 * 这是 Repository + Android 加密存储 + Application recovery/relay 的真实设备链路；
 * 不经过 Compose 和 SAF，因此不得称为 Backup UI E2E。
 */
class AndroidRestoreRelayEndToEndTest {
    @Test
    fun relayPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(ARG_PHASE)
        assumeTrue(phase in setOf(PHASE_EXPORT, PHASE_STAGE, PHASE_VERIFY, PHASE_NO_REPLAY))
        when (phase) {
            PHASE_EXPORT -> exportEncryptedPackage()
            PHASE_STAGE -> stageAndRestart()
            PHASE_VERIFY -> verifyAndPrepareNoReplay()
            PHASE_NO_REPLAY -> verifyNoReplay()
        }
    }

    private suspend fun exportEncryptedPackage() {
        val app = app()
        awaitReady(app)
        assertThat(pendingFile(app).exists()).isFalse()
        exportFile(app).delete()
        proof(app).edit().clear().commit()

        app.database.clearAllTables()
        app.database.agentDao().insert(
            AgentEntity(RESTORED_AGENT, "encrypted-include-keys-agent", createdAt = 1),
        )
        settings(app).edit().putString(PREFERENCE_KEY, RESTORED_PREFERENCE).commit()
        putSecret(app, RESTORED_SECRET)

        val password = password()
        val confirmation = password()
        val bytes = try {
            BackupRepository(app).export(
                BackupExportOptions(
                    includeSecrets = true,
                    password = password,
                    passwordConfirmation = confirmation,
                ),
            )
        } finally {
            password.fill('\u0000')
            confirmation.fill('\u0000')
        }
        try {
            val decodePassword = password()
            try {
                DefaultBackupPackageCodec().decode(bytes, decodePassword).use { validated ->
                    assertThat(validated.manifest.encrypted).isTrue()
                    assertThat(validated.manifest.containsSecrets).isTrue()
                    assertThat(validated.secrets.keys).contains(SECRET_ID)
                    assertThat(validated.secrets.getValue(SECRET_ID).toString(Charsets.UTF_8))
                        .isEqualTo(RESTORED_SECRET)
                }
            } finally {
                decodePassword.fill('\u0000')
            }
            FileOutputStream(exportFile(app)).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        } finally {
            bytes.fill(0)
        }

        val txId = UUID.randomUUID().toString()
        proof(app).edit()
            .putString(KEY_TX_ID, txId)
            .putInt(KEY_EXPORT_PID, Process.myPid())
            .commit()

        app.database.clearAllTables()
        app.database.agentDao().insert(
            AgentEntity(BEFORE_AGENT, "must-be-replaced", createdAt = 2),
        )
        settings(app).edit().putString(PREFERENCE_KEY, BEFORE_PREFERENCE).commit()
        putSecret(app, BEFORE_SECRET)
    }

    private suspend fun stageAndRestart() {
        val app = app()
        awaitReady(app)
        val txId = requireNotNull(proof(app).getString(KEY_TX_ID, null))
        assertThat(Process.myPid()).isNotEqualTo(proof(app).getInt(KEY_EXPORT_PID, -1))
        val packageFile = exportFile(app)
        assertThat(packageFile.exists()).isTrue()
        val bytes = packageFile.readBytes()
        val stagePassword = password()
        try {
            BackupRepository(app).run {
                stageLocalRestore(txId, ByteArrayInputStream(bytes), stagePassword)
                authorizePendingRestore(txId)
            }
        } finally {
            bytes.fill(0)
            stagePassword.fill('\u0000')
            assertThat(packageFile.delete()).isTrue()
        }

        AndroidPendingRestoreStore(app).read()!!.use { pending ->
            assertThat(pending.metadata.txId).isEqualTo(txId)
            assertThat(pending.metadata.phase).isEqualTo(PendingRestorePhase.STAGED)
            assertThat(pending.packageBytes).isNotEmpty()
            assertThat(pending.password?.concatToString()).isEqualTo(PASSWORD)
        }
        proof(app).edit().putInt(KEY_STAGE_PID, Process.myPid()).commit()

        RestoreRelayActivity.requestRestart(app)
        // relay 必须杀死当前主进程；如果仍执行到这里，门禁失败。
        delay(5_000)
        error("relay did not terminate main process")
    }

    private suspend fun verifyAndPrepareNoReplay() {
        val app = app()
        awaitReady(app)
        val txId = requireNotNull(proof(app).getString(KEY_TX_ID, null))
        assertThat(Process.myPid()).isNotEqualTo(proof(app).getInt(KEY_STAGE_PID, -1))
        assertThat(app.database.agentDao().getAll().map { it.id }).containsExactly(RESTORED_AGENT)
        assertThat(settings(app).getString(PREFERENCE_KEY, null)).isEqualTo(RESTORED_PREFERENCE)
        assertSecret(app, RESTORED_SECRET)
        assertThat(hasReceipt(app, txId)).isTrue()
        assertThat(pendingFile(app).exists()).isFalse()
        assertThat(exportFile(app).exists()).isFalse()

        app.database.agentDao().insert(
            AgentEntity(NO_REPLAY_AGENT, "survives-next-start", createdAt = 3),
        )
        settings(app).edit().putString(PREFERENCE_KEY, NO_REPLAY_PREFERENCE).commit()
        putSecret(app, NO_REPLAY_SECRET)
        proof(app).edit().putInt(KEY_VERIFY_PID, Process.myPid()).commit()
    }

    private suspend fun verifyNoReplay() {
        val app = app()
        awaitReady(app)
        val txId = requireNotNull(proof(app).getString(KEY_TX_ID, null))
        assertThat(Process.myPid()).isNotEqualTo(proof(app).getInt(KEY_VERIFY_PID, -1))
        assertThat(app.database.agentDao().getAll().map { it.id })
            .containsExactly(RESTORED_AGENT, NO_REPLAY_AGENT)
        assertThat(settings(app).getString(PREFERENCE_KEY, null)).isEqualTo(NO_REPLAY_PREFERENCE)
        assertSecret(app, NO_REPLAY_SECRET)
        assertThat(hasReceipt(app, txId)).isTrue()
        assertThat(pendingFile(app).exists()).isFalse()
        assertThat(exportFile(app).exists()).isFalse()

        app.secretStore.remove(SECRET_ID)
        proof(app).edit().clear().commit()
    }

    private suspend fun awaitReady(app: NexaraApplication) {
        withTimeout(30_000) { app.startupState.first { it != BackupStartupState.Recovering } }
        assertThat(app.startupState.value).isEqualTo(BackupStartupState.Ready)
    }

    private fun putSecret(app: NexaraApplication, value: String) {
        val bytes = value.toByteArray()
        try {
            app.secretStore.put(SECRET_ID, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun assertSecret(app: NexaraApplication, expected: String) {
        val bytes = requireNotNull(app.secretStore.get(SECRET_ID))
        try {
            assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo(expected)
        } finally {
            bytes.fill(0)
        }
    }

    private fun hasReceipt(app: NexaraApplication, txId: String): Boolean =
        app.database.openHelper.writableDatabase.query(
            "SELECT 1 FROM audit_logs WHERE id = ? AND action = ? AND status = ?",
            arrayOf("__nexara_restore_$txId", "__nexara_restore_commit", "completed"),
        ).use { it.moveToFirst() }

    private fun app() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NexaraApplication

    private fun proof(context: Context) = context.getSharedPreferences(PROOF_PREFS, Context.MODE_PRIVATE)
    private fun settings(context: Context) = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun pendingFile(context: Context) =
        context.noBackupFilesDir.resolve(AndroidPendingRestoreStore.FILE_NAME)
    private fun exportFile(context: Context): File = context.noBackupFilesDir.resolve(EXPORT_FILE)
    private fun password() = PASSWORD.toCharArray()

    private companion object {
        const val ARG_PHASE = "restoreRelayPhase"
        const val PHASE_EXPORT = "export"
        const val PHASE_STAGE = "stage"
        const val PHASE_VERIFY = "verify"
        const val PHASE_NO_REPLAY = "noReplay"
        const val PROOF_PREFS = "restore_relay_e2e_proof"
        const val SETTINGS_PREFS = "nexara_settings"
        const val PREFERENCE_KEY = "user_name"
        const val EXPORT_FILE = "restore-relay-e2e-export.nexara"
        const val KEY_TX_ID = "tx_id"
        const val KEY_EXPORT_PID = "export_pid"
        const val KEY_STAGE_PID = "stage_pid"
        const val KEY_VERIFY_PID = "verify_pid"
        const val RESTORED_AGENT = "relay-encrypted-restored-agent"
        const val BEFORE_AGENT = "relay-encrypted-before-agent"
        const val NO_REPLAY_AGENT = "relay-encrypted-no-replay-agent"
        const val RESTORED_PREFERENCE = "backup-e2e-restored-user"
        const val BEFORE_PREFERENCE = "backup-e2e-before-user"
        const val NO_REPLAY_PREFERENCE = "backup-e2e-no-replay-user"
        const val RESTORED_SECRET = "e2e-fictional-restored-provider-key"
        const val BEFORE_SECRET = "e2e-fictional-before-provider-key"
        const val NO_REPLAY_SECRET = "e2e-fictional-no-replay-provider-key"
        const val PASSWORD = "Nexara-E2E-Only-Password-2026!"
        val SECRET_ID = SecretCatalog.providerApiKey("default")
    }
}
