package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.ui.settings.ModelInfo
import com.promenar.nexara.ui.settings.persistVerifiedProviderConnection
import kotlinx.coroutines.test.runTest
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
        assertThat(manager.getMainConfiguredModelId()).isEqualTo("default::remote-model")
        assertThat(manager.getProviderConfigByModelId(stored.id)?.apiKey).isEqualTo("test-key")

        manager.setPresetModel("summary", stored.id)
        assertThat(manager.summaryModelId.value).isEqualTo("default::remote-model")
        assertThat(runCatching { manager.setPresetModel("summary", "remote-model") }.isFailure).isTrue()
    }

    @Test
    fun `连接探测后修改地址和 Key 必须保存且可由 ProviderManager 反读`() = runTest {
        val updatedUrl = "https://updated-provider.invalid/v1"
        val updatedKey = "updated-key"

        val savedId = persistVerifiedProviderConnection(
            protocolType = ProtocolType.OpenAI_ChatCompletions,
            baseUrl = updatedUrl,
            credential = CredentialUpdate.Replace(updatedKey),
            model = "",
            name = "更新后的提供商",
            onSave = { protocol, url, credential, model, name ->
                manager.updateMainProvider(protocol, url, credential, model, name)
                "default"
            },
            readSummary = manager::getProviderSummary,
        )

        assertThat(savedId).isEqualTo("default")
        assertThat(manager.getProviderSummary("default")?.baseUrl).isEqualTo(updatedUrl)
        assertThat(manager.getMainProviderConfig()?.apiKey).isEqualTo(updatedKey)
    }

    @Test
    fun `缺失 providerId 不按 providerName 模糊回退`() {
        manager.addModel(model(providerId = null, id = "unowned"))

        assertThat(manager.getProviderConfigByModelId("unowned")).isNull()
    }

    @Test
    fun `v0_2 不对旧裸模型 ID 做半迁移且 Router typed fail`() {
        val prefs = app.getSharedPreferences("nexara_settings", 0)
        prefs.edit()
            .clear()
            .putStringSet("all_models", setOf("legacy-model"))
            .putStringSet("enabled_models", setOf("legacy-model"))
            .putString("all_models_order", "legacy-model")
            .putString("model_info_legacy-model_name", "Legacy")
            .putString("model_info_legacy-model_provider_id", "default")
            .putString("model_info_legacy-model_provider", "同名提供商")
            .commit()
        val isolated = ProviderManager.createForTest(app, TestSecretStore()).also {
            it.updateMainProvider(
                ProtocolType.OpenAI_ChatCompletions,
                "https://provider.invalid",
                CredentialUpdate.Replace("test-key"),
                "remote-model",
                "同名提供商",
            )
        }

        assertThat(isolated.providerModels.value.map { it.id })
            .containsAtLeast("legacy-model", "default::remote-model")
        val failure = DefaultProviderRequestRouter(isolated).resolve("legacy-model") as ProviderResolution.Failure
        assertThat(failure.reason).isEqualTo(ProviderResolutionError.MODEL_PROVIDER_MISMATCH)
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
