package com.promenar.nexara.data.backup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.AndroidKeystoreSecretStore
import com.promenar.nexara.data.security.SecretId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.security.KeyStore
import java.util.Base64
import android.util.AtomicFile

/** 使用两次独立 am instrument invocation 的 backupPhase 参数，以证明进程重建持久性。 */
class AndroidBackupProcessRecreationTest {
    @Test
    fun processRecreationPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("backupPhase")
        assumeTrue("仅由显式两阶段进程重建命令运行", phase == "prepare" || phase == "commit")
        if (phase == "prepare") preparePhase() else commitPhase()
    }

    private suspend fun preparePhase() {
        val context = context()
        listOf(LIVE_PREFS, STAGING_PREFS, METADATA_PREFS, PROOF_PREFS).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        deleteAlias(LIVE_ALIAS)
        deleteAlias(STAGING_ALIAS)
        deleteAlias(HMAC_ALIAS)
        deleteAlias(PENDING_ALIAS)
        AtomicFile(context.noBackupFilesDir.resolve(PENDING_FILE)).delete()
        val id = SecretId("provider_api_key:process-proof")
        val live = AndroidKeystoreSecretStore(context, LIVE_PREFS, LIVE_ALIAS)
        val staging = AndroidKeystoreSecretStore(context, STAGING_PREFS, STAGING_ALIAS)
        live.put(id, "process-before".toByteArray())
        store(context, live, staging).prepare(
            TX_ID,
            mapOf(id to "process-before".toByteArray()),
            mapOf(id to "process-after".toByteArray()),
        )
        val payload = "process-hmac-payload".toByteArray()
        val signature = AndroidRestoreJournalAuthenticator(HMAC_ALIAS).sign(payload)
        context.getSharedPreferences(PROOF_PREFS, Context.MODE_PRIVATE).edit()
            .putString("signature", Base64.getEncoder().encodeToString(signature))
            .commit()
        signature.fill(0)
        pendingStore(context).run {
            begin(PENDING_TX_ID)
            stage(PENDING_TX_ID, "pending-process-package".toByteArray(), "pending-process-password".toCharArray())
            authorize(PENDING_TX_ID)
        }
    }

    private suspend fun commitPhase() {
        val context = context()
        val id = SecretId("provider_api_key:process-proof")
        val live = AndroidKeystoreSecretStore(context, LIVE_PREFS, LIVE_ALIAS)
        val staging = AndroidKeystoreSecretStore(context, STAGING_PREFS, STAGING_ALIAS)
        store(context, live, staging).commitPrepared(TX_ID)
        val value = live.get(id)!!
        try {
            assertThat(value.toString(Charsets.UTF_8)).isEqualTo("process-after")
        } finally {
            value.fill(0)
        }
        val signature = Base64.getDecoder().decode(
            context.getSharedPreferences(PROOF_PREFS, Context.MODE_PRIVATE).getString("signature", null)
        )
        try {
            assertThat(AndroidRestoreJournalAuthenticator(HMAC_ALIAS).verify(
                "process-hmac-payload".toByteArray(),
                signature,
            )).isTrue()
        } finally {
            signature.fill(0)
        }
        store(context, live, staging).finalizePrepared(TX_ID)
        pendingStore(context).read()!!.use { pending ->
            assertThat(pending.metadata.txId).isEqualTo(PENDING_TX_ID)
            assertThat(pending.packageBytes.toString(Charsets.UTF_8)).isEqualTo("pending-process-package")
            assertThat(pending.password!!.concatToString()).isEqualTo("pending-process-password")
        }
        pendingStore(context).clear(PENDING_TX_ID)
        deleteAlias(LIVE_ALIAS)
        deleteAlias(STAGING_ALIAS)
        deleteAlias(HMAC_ALIAS)
        deleteAlias(PENDING_ALIAS)
    }

    private fun store(
        context: Context,
        live: AndroidKeystoreSecretStore,
        staging: AndroidKeystoreSecretStore,
    ) = AndroidTransactionalBackupSecretStore(
        liveStore = live,
        stagingStore = staging,
        metadataPreferences = context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE),
    )

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pendingStore(context: Context) = AndroidPendingRestoreStore(
        AtomicFile(context.noBackupFilesDir.resolve(PENDING_FILE)),
        AndroidKeystorePendingRestoreCryptor(PENDING_ALIAS),
    )

    private fun deleteAlias(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val TX_ID = "process-recreation-proof"
        const val LIVE_PREFS = "test_process_live"
        const val STAGING_PREFS = "test_process_staging"
        const val METADATA_PREFS = "test_process_metadata"
        const val PROOF_PREFS = "test_process_proof"
        const val LIVE_ALIAS = "nexara.test.process.live"
        const val STAGING_ALIAS = "nexara.test.process.staging"
        const val HMAC_ALIAS = "nexara.test.process.hmac"
        const val PENDING_ALIAS = "nexara.test.process.pending"
        const val PENDING_FILE = "pending-process-proof.bin"
        const val PENDING_TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
