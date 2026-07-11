package com.promenar.nexara.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderRepositorySecretTest {
    private lateinit var app: Application
    private lateinit var manager: ProviderManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("nexara_provider", "nexara_settings", "nexara_search", "nexara_backup_settings")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        manager = ProviderManager.createForTest(app, MemorySecretStore())
    }

    @Test
    fun `保存脱敏观察对象不会清除已有主 Provider Key`() = runTest {
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://before.invalid",
            CredentialUpdate.Replace("fake-existing-key"),
            "model-a",
            "Main",
        )
        val repository = ProviderRepository(manager)
        val redacted = repository.observeAll().first().single()

        repository.save(redacted.copy(baseUrl = "https://after.invalid"))

        assertThat(manager.getMainProviderConfig()!!.apiKey).isEqualTo("fake-existing-key")
        assertThat(manager.getMainProviderConfig()!!.baseUrl).isEqualTo("https://after.invalid")
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }
}
