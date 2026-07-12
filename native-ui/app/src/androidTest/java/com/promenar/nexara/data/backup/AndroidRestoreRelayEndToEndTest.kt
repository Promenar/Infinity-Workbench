package com.promenar.nexara.data.backup

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.repository.BackupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** 由三次独立 am instrument 调用驱动，以验证 relay 真正杀进程、冷启动恢复和再次重启不重放。 */
class AndroidRestoreRelayEndToEndTest {
    @Test
    fun relayPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString(ARG_PHASE)
        assumeTrue(phase in setOf(PHASE_STAGE, PHASE_PREPARE, PHASE_VERIFY, PHASE_NO_REPLAY))
        when (phase) {
            PHASE_STAGE -> stageAndRestart()
            PHASE_PREPARE -> preparePending()
            PHASE_VERIFY -> verifyAndPrepareNoReplay()
            PHASE_NO_REPLAY -> verifyNoReplay()
        }
    }

    private suspend fun stageAndRestart() {
        preparePending()
        RestoreRelayActivity.requestRestart(app())
        // relay 会杀死本进程；如果仍执行到这里，测试必须失败。
        kotlinx.coroutines.delay(5_000)
        error("relay did not terminate main process")
    }

    private suspend fun preparePending() {
        val app = app()
        awaitReady(app)
        val pending = app.noBackupFilesDir.resolve(AndroidPendingRestoreStore.FILE_NAME)
        pending.delete()
        app.database.clearAllTables()
        app.database.agentDao().insert(AgentEntity(RESTORED_AGENT, "restored-once", createdAt = 1))

        val repository = BackupRepository(app)
        val bytes = repository.export()
        val txId = UUID.randomUUID().toString()
        val metadata = try {
            AndroidPendingRestoreStore(app).run {
                begin(txId)
                stage(txId, bytes, null)
                authorize(txId)
            }
        } finally {
            bytes.fill(0)
        }
        proof(app).edit()
            .putString(KEY_TX_ID, metadata.txId)
            .putInt(KEY_STAGE_PID, Process.myPid())
            .commit()

        app.database.clearAllTables()
        app.database.agentDao().insert(AgentEntity(BEFORE_AGENT, "must-be-replaced", createdAt = 2))
    }

    private suspend fun verifyAndPrepareNoReplay() {
        val app = app()
        awaitReady(app)
        val txId = requireNotNull(proof(app).getString(KEY_TX_ID, null))
        assertThat(app.database.agentDao().getAll().map { it.id }).containsExactly(RESTORED_AGENT)
        assertThat(hasReceipt(app, txId)).isTrue()
        assertThat(app.noBackupFilesDir.resolve(AndroidPendingRestoreStore.FILE_NAME).exists()).isFalse()
        app.database.agentDao().insert(AgentEntity(NO_REPLAY_AGENT, "survives-next-start", createdAt = 3))
    }

    private suspend fun verifyNoReplay() {
        val app = app()
        awaitReady(app)
        val txId = requireNotNull(proof(app).getString(KEY_TX_ID, null))
        assertThat(app.database.agentDao().getAll().map { it.id })
            .containsExactly(RESTORED_AGENT, NO_REPLAY_AGENT)
        assertThat(hasReceipt(app, txId)).isTrue()
        assertThat(app.noBackupFilesDir.resolve(AndroidPendingRestoreStore.FILE_NAME).exists()).isFalse()
        proof(app).edit().clear().commit()
    }

    private suspend fun awaitReady(app: NexaraApplication) {
        withTimeout(30_000) { app.startupState.first { it != BackupStartupState.Recovering } }
        assertThat(app.startupState.value).isEqualTo(BackupStartupState.Ready)
    }

    private fun hasReceipt(app: NexaraApplication, txId: String): Boolean =
        app.database.openHelper.writableDatabase.query(
            "SELECT 1 FROM audit_logs WHERE id = ? AND action = ? AND status = ?",
            arrayOf("__nexara_restore_$txId", "__nexara_restore_commit", "completed"),
        ).use { it.moveToFirst() }

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NexaraApplication
    private fun proof(context: Context) = context.getSharedPreferences(PROOF_PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val ARG_PHASE = "restoreRelayPhase"
        const val PHASE_STAGE = "stage"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_NO_REPLAY = "noReplay"
        const val PROOF_PREFS = "restore_relay_e2e_proof"
        const val KEY_TX_ID = "tx_id"
        const val KEY_STAGE_PID = "stage_pid"
        const val RESTORED_AGENT = "relay-restored-agent"
        const val BEFORE_AGENT = "relay-before-agent"
        const val NO_REPLAY_AGENT = "relay-no-replay-agent"
    }
}
