package com.promenar.nexara.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class AndroidTransactionalBackupSecretStoreTest {
    private lateinit var context: Context
    private lateinit var live: MemorySecretStore
    private lateinit var staging: MemorySecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(METADATA, Context.MODE_PRIVATE).edit().clear().commit()
        live = MemorySecretStore()
        staging = MemorySecretStore()
    }

    @Test
    fun `commit survives adapter recreation and treats missing after values as deletion`() = runBlocking {
        val first = SecretId("provider_api_key:test-a")
        val removed = SecretId("provider_api_key:test-b")
        live.put(first, "old-a".toByteArray())
        live.put(removed, "old-b".toByteArray())
        val before = mapOf(first to "old-a".toByteArray(), removed to "old-b".toByteArray())
        val after = mapOf(first to "new-a".toByteArray())

        newStore().prepare("tx-commit", before, after)
        newStore().commitPrepared("tx-commit")

        assertThat(live.text(first)).isEqualTo("new-a")
        assertThat(live.contains(removed)).isFalse()
        newStore().commitPrepared("tx-commit")
        newStore().finalizePrepared("tx-commit")
        newStore().finalizePrepared("tx-commit")
        newStore().commitPrepared("tx-commit")
    }

    @Test
    fun `rollback after partial commit restores before values after recreation`() = runBlocking {
        val first = SecretId("provider_api_key:first")
        val second = SecretId("provider_api_key:second")
        live.put(first, "old-first".toByteArray())
        live.put(second, "old-second".toByteArray())
        val before = mapOf(first to "old-first".toByteArray(), second to "old-second".toByteArray())
        val after = mapOf(first to "new-first".toByteArray(), second to "new-second".toByteArray())
        newStore().prepare("tx-partial", before, after)
        live.failPutOnceFor = second

        var failed = false
        try {
            newStore().commitPrepared("tx-partial")
        } catch (_: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
        newStore().rollbackPrepared("tx-partial")

        assertThat(live.text(first)).isEqualTo("old-first")
        assertThat(live.text(second)).isEqualTo("old-second")
        newStore().rollbackPrepared("tx-partial")
        newStore().finalizePrepared("tx-partial")
    }

    @Test
    fun `automatic backup password is excluded and never modified`() = runBlocking {
        val eligible = SecretId("tavily_api_key")
        live.put(eligible, "search-key".toByteArray())
        live.put(SecretCatalog.automaticBackupPassword, "automatic-only".toByteArray())

        val snapshot = newStore().snapshot(
            setOf(eligible, SecretCatalog.automaticBackupPassword),
            1024,
        )
        assertThat(snapshot.keys).containsExactly(eligible)
        snapshot.values.forEach { it.fill(0) }
        newStore().prepare(
            "tx-auto",
            mapOf(SecretCatalog.automaticBackupPassword to "before".toByteArray()),
            mapOf(SecretCatalog.automaticBackupPassword to "after".toByteArray()),
        )
        newStore().commitPrepared("tx-auto")

        assertThat(live.text(SecretCatalog.automaticBackupPassword)).isEqualTo("automatic-only")
    }

    @Test
    fun `metadata and staging never contain plaintext and snapshot enforces budget`() = runBlocking {
        val id = SecretId("embedding_api_key")
        live.put(id, "plaintext-secret".toByteArray())
        val before = mapOf(id to "plaintext-secret".toByteArray())
        val after = mapOf(id to "replacement-secret".toByteArray())
        newStore().prepare("tx-no-plain", before, after)

        val metadataText = context.getSharedPreferences(METADATA, Context.MODE_PRIVATE).all.values.joinToString()
        assertThat(metadataText).doesNotContain("plaintext-secret")
        assertThat(metadataText).doesNotContain("replacement-secret")
        assertThat(staging.rawText()).doesNotContain("plaintext-secret")
        assertThat(staging.rawText()).doesNotContain("replacement-secret")

        var failed = false
        try {
            newStore().snapshot(setOf(id), 1)
        } catch (_: BackupValidationException) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    @Test
    fun `partial prepare rolls back without changing live secrets after recreation`() = runBlocking {
        val id = SecretId("provider_api_key:prepare")
        live.put(id, "live-before".toByteArray())
        staging.failNextPut = true

        var prepareFailed = false
        try {
            newStore().prepare(
                "tx-prepare-failure",
                mapOf(id to "live-before".toByteArray()),
                mapOf(id to "live-after".toByteArray()),
            )
        } catch (_: Exception) {
            prepareFailed = true
        }
        assertThat(prepareFailed).isTrue()

        newStore().rollbackPrepared("tx-prepare-failure")
        newStore().finalizePrepared("tx-prepare-failure")
        assertThat(live.text(id)).isEqualTo("live-before")
        assertThat(runCatching { newStore().commitPrepared("never-prepared") }.isFailure).isTrue()
    }

    private fun newStore() = AndroidTransactionalBackupSecretStore(
        liveStore = live,
        stagingStore = staging,
        metadataPreferences = context.getSharedPreferences(METADATA, Context.MODE_PRIVATE),
    )

    private class MemorySecretStore : SecretStore {
        private val values = ConcurrentHashMap<SecretId, ByteArray>()
        var failPutOnceFor: SecretId? = null
        var failNextPut: Boolean = false

        override fun put(id: SecretId, value: ByteArray) {
            if (failNextPut) {
                failNextPut = false
                throw IllegalStateException("injected staging failure")
            }
            if (failPutOnceFor == id) {
                failPutOnceFor = null
                throw IllegalStateException("injected put failure")
            }
            values.put(id, value.copyOf())?.fill(0)
        }

        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = values.containsKey(id)
        override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
        fun text(id: SecretId): String? = get(id)?.let { bytes ->
            try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        }
        fun rawText(): String = values.values.joinToString { "encrypted:${it.size}" }
    }

    private companion object {
        const val METADATA = "test_backup_secret_transactions"
    }
}
