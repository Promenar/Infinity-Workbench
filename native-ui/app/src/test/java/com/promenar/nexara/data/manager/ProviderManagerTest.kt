package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderManagerTest {
    private lateinit var app: Application
    private lateinit var manager: ProviderManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("nexara_provider", "nexara_settings").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
        manager = ProviderManager.createForTest(app, TestSecretStore())
        manager.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://provider.invalid",
            CredentialUpdate.Replace("test-key"),
            "remote-model",
            "同名提供商",
        )
    }

    @Test
    fun `新增模型使用稳定复合标识并保留远端模型 ID`() {
        val stored = manager.providerModels.value.single()
        assertThat(stored.id).isEqualTo("default::remote-model")
        assertThat(stored.remoteModelId).isEqualTo("remote-model")
        assertThat(manager.getProviderConfigByModelId(stored.id)?.apiKey).isEqualTo("test-key")
    }

    @Test
    fun `缺失 providerId 不按 providerName 模糊回退`() {
        manager.addModel(model(providerId = null, id = "unowned"))

        assertThat(manager.getProviderConfigByModelId("unowned")).isNull()
    }

    private fun model(providerId: String?, id: String) = ModelInfo(
        name = id,
        id = id,
        description = "",
        enabled = true,
        providerName = "同名提供商",
        providerId = providerId,
        remoteModelId = id,
    )
}

private class TestSecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    override fun contains(id: SecretId): Boolean = values.containsKey(id)
}
