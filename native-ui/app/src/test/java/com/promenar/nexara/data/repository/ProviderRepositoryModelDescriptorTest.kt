package com.promenar.nexara.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.catalog.ModelMetadataOverride
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.RemoteModelDescriptor
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderRepositoryModelDescriptorTest {
    private lateinit var app: Application
    private lateinit var manager: ProviderManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("nexara_provider", "nexara_settings").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        manager = ProviderManager.createForTest(app, TestSecretStore()).also {
            it.updateMainProvider(
                ProtocolType.Generic_OpenAI_Compat,
                "https://provider.invalid",
                CredentialUpdate.Replace("test-key"),
                "",
                "测试提供商",
            )
        }
    }

    @Test
    fun `descriptor API逐字符返回ID且兼容fetchModels投影富名称和类型`() = runTest {
        val descriptor = RemoteModelDescriptor(
            id = "Tenant::Embed-X",
            ownedBy = "Vendor",
            metadata = ModelMetadataOverride(
                displayName = "Embedding X",
                workload = ModelWorkload.EMBEDDING,
            ),
        )
        val repository = ProviderRepository(
            providerManager = manager,
            modelDescriptorFetcher = { listOf(descriptor) },
        )

        assertThat(repository.fetchModelDescriptors("default")).containsExactly(descriptor)
        val projected = repository.fetchModels("default").single()
        assertThat(projected.id).isEqualTo("Tenant::Embed-X")
        assertThat(projected.name).isEqualTo("Embedding X")
        assertThat(projected.type.name).isEqualTo("EMBEDDING")
    }
}

private class TestSecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    override fun contains(id: SecretId): Boolean = id in values
}
