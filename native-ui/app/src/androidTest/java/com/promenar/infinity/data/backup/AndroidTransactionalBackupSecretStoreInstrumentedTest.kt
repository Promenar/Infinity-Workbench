package com.promenar.infinity.data.backup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AndroidTransactionalBackupSecretStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun clearTransactionStores() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("infinity_backup_secret_transactions_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_backup_secret_staging_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun keystoreEncryptedStagingSurvivesInstanceRecreationWithoutPlaintext() = runBlocking {
        val liveName = "test-live-secrets-${UUID.randomUUID()}"
        val live = AndroidKeystoreSecretStore(context, liveName)
        val id = SecretId("provider_api_key:instrumented")
        val automatic = SecretCatalog.automaticBackupPassword
        live.put(id, "device-before-secret".toByteArray())
        live.put(automatic, "device-auto-password".toByteArray())
        val first = AndroidTransactionalBackupSecretStore(context, live)
        first.prepare(
            "device-${UUID.randomUUID()}",
            mapOf(id to "device-before-secret".toByteArray()),
            mapOf(id to "device-after-secret".toByteArray(), automatic to "must-not-stage".toByteArray()),
        )
        val txId = context.getSharedPreferences("infinity_backup_secret_transactions_v1", Context.MODE_PRIVATE)
            .all.keys.single().removePrefix("tx:")

        val diskText = context.getSharedPreferences("infinity_backup_secret_staging_v1", Context.MODE_PRIVATE)
            .all.values.joinToString()
        assertThat(diskText).doesNotContain("device-before-secret")
        assertThat(diskText).doesNotContain("device-after-secret")
        assertThat(diskText).doesNotContain("must-not-stage")

        AndroidTransactionalBackupSecretStore(
            context,
            AndroidKeystoreSecretStore(context, liveName),
        ).commitPrepared(txId)

        assertThat(readText(live, id)).isEqualTo("device-after-secret")
        assertThat(readText(live, automatic)).isEqualTo("device-auto-password")
    }

    private fun readText(store: AndroidKeystoreSecretStore, id: SecretId): String? = store.get(id)?.let { bytes ->
        try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
