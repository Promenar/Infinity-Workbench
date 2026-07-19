package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.catalog.BundledModelCatalog
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.settings.persistVerifiedProviderConnection
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    @Test
    fun `provider scoped disable only changes the requested provider`() {
        addExtraProviderWithModel()
        val defaultId = "default::remote-model"
        val extraId = "extra-a::extra-model"

        manager.disableAllModels("default")

        assertThat(manager.providerModels.value.single { it.id == defaultId }.enabled).isFalse()
        assertThat(manager.providerModels.value.single { it.id == extraId }.enabled).isTrue()
    }

    @Test
    fun `provider scoped delete preserves hidden provider models and persistence`() {
        addExtraProviderWithModel()

        manager.deleteAllModels("default")

        assertThat(manager.providerModels.value.map { it.id }).containsExactly("extra-a::extra-model")
        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(reloaded.providerModels.value.map { it.id }).containsExactly("extra-a::extra-model")
    }

    @Test
    fun `custom model uses requested provider stable id display name and persists`() {
        addExtraProviderWithModel()

        assertThat(manager.addCustomModel("extra-a", "  custom-model  ", "  Custom Name  ")).isTrue()
        assertThat(manager.addCustomModel("extra-a", "custom-model", "duplicate")).isFalse()

        val stored = manager.providerModels.value.single { it.id == "extra-a::custom-model" }
        assertThat(stored.remoteModelId).isEqualTo("custom-model")
        assertThat(stored.providerId).isEqualTo("extra-a")
        assertThat(stored.providerName).isEqualTo("隐藏提供商")
        assertThat(stored.name).isEqualTo("Custom Name")
        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        assertThat(reloaded.providerModels.value.single { it.id == "extra-a::custom-model" }.name)
            .isEqualTo("Custom Name")
    }

    @Test
    fun `旧版自动生成的 DeepSeek V4 Flash 指纹整体迁移到新规格`() {
        persistLegacyDeepSeekV4FlashFingerprint()

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val migrated = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(migrated.id).isEqualTo("default::deepseek-v4-flash")
            assertThat(migrated.remoteModelId).isEqualTo("deepseek-v4-flash")
            assertThat(migrated.providerId).isEqualTo("default")
            assertThat(migrated.enabled).isTrue()
            assertThat(migrated.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(migrated.type).isEqualTo("reasoning")
            assertThat(migrated.contextLength).isEqualTo(1000000)
            assertThat(migrated.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput")
            assertThat(migrated.maxOutputTokens).isEqualTo(384000)
            assertThat(migrated.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧指纹名称被用户修改后只保护名称`() {
        persistLegacyDeepSeekV4FlashFingerprint(name = "我的 DeepSeek")

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val preserved = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(preserved.name).isEqualTo("我的 DeepSeek")
            assertThat(preserved.type).isEqualTo("reasoning")
            assertThat(preserved.contextLength).isEqualTo(1000000)
            assertThat(preserved.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput")
            assertThat(preserved.maxOutputTokens).isEqualTo(384000)
            assertThat(preserved.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧指纹上下文被用户修改后只保护上下文`() {
        persistLegacyDeepSeekV4FlashFingerprint(contextLength = 131072)

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val preserved = reloaded.providerModels.value.single {
                it.id == "default::deepseek-v4-flash"
            }

            assertThat(preserved.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(preserved.type).isEqualTo("reasoning")
            assertThat(preserved.contextLength).isEqualTo(131072)
            assertThat(preserved.capabilities)
                .containsExactly("chat", "reasoning", "structuredoutput")
            assertThat(preserved.maxOutputTokens).isEqualTo(384000)
            assertThat(preserved.knowledgeCutoff).isEqualTo("2025-05")
        }
    }

    @Test
    fun `旧 fetched unknown empty 8192 由 SharedPreferences 加载后全部保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "future-model",
            name = "future-model",
            type = "unknown",
            contextLength = 8192,
            capabilities = emptySet(),
            maxOutputTokens = 0,
        )

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        val migrated = reloaded.providerModels.value.single { it.remoteModelId == "future-model" }

        assertThat(migrated.userEditedFields).isEmpty()
        assertThat(migrated.type).isEqualTo("unknown")
        assertThat(migrated.contextLength).isEqualTo(0)
        assertThat(migrated.capabilities).isEmpty()
    }

    @Test
    fun `旧 configured chat chat 8192 由 SharedPreferences 加载后全部保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "future-model",
            name = "future-model",
            type = "chat",
            contextLength = 8192,
            capabilities = setOf("chat"),
            maxOutputTokens = 0,
        )

        val reloaded = ProviderManager.createForTest(app, TestSecretStore())
        val migrated = reloaded.providerModels.value.single { it.remoteModelId == "future-model" }

        assertThat(migrated.userEditedFields).isEmpty()
        assertThat(migrated.type).isEqualTo("unknown")
        assertThat(migrated.contextLength).isEqualTo(0)
        assertThat(migrated.capabilities).isEmpty()
    }

    @Test
    fun `Task4 精确 findModelSpec 形状由 SharedPreferences 加载后保持自动所有权`() {
        persistLegacyModel(
            remoteModelId = "deepseek-v4-flash",
            name = "DeepSeek V4 Flash",
            type = "reasoning",
            contextLength = 1_000_000,
            capabilities = setOf("chat", "reasoning", "structuredoutput"),
            maxOutputTokens = 384_000,
        )

        withBundledCatalog {
            val reloaded = ProviderManager.createForTest(app, TestSecretStore())
            val migrated = reloaded.providerModels.value.single {
                it.remoteModelId == "deepseek-v4-flash"
            }

            assertThat(migrated.userEditedFields).isEmpty()
            assertThat(migrated.name).isEqualTo("DeepSeek V4 Flash")
            assertThat(migrated.contextLength).isEqualTo(1_000_000)
            assertThat(migrated.maxOutputTokens).isEqualTo(384_000)
        }
    }

    @Test
    fun `deleting and readding provider clears prior model suppression`() {
        addExtraProviderWithModel()
        manager.deleteAllModels("extra-a")
        manager.deleteProvider("extra-a")

        addExtraProviderWithModel()

        assertThat(manager.providerModels.value.map { it.id }).contains("extra-a::extra-model")
    }

    private fun addExtraProviderWithModel() {
        manager.addProvider(
            ProviderListItem(
                id = "extra-a",
                name = "隐藏提供商",
                baseUrl = "https://extra.invalid",
                model = "extra-model",
                protocolType = ProtocolType.OpenAI_ChatCompletions,
                enabled = true,
            ),
            CredentialUpdate.Replace("extra-test-key"),
        )
    }

    private fun persistLegacyDeepSeekV4FlashFingerprint(
        name: String = "DeepSeek",
        contextLength: Int = 64000,
    ) {
        val id = "default::deepseek-v4-flash"
        val prefix = "model_info_$id"
        app.getSharedPreferences("nexara_settings", 0).edit()
            .clear()
            .putStringSet("all_models", setOf(id))
            .putStringSet("enabled_models", setOf(id))
            .putString("all_models_order", id)
            .putString("${prefix}_name", name)
            .putString("${prefix}_type", "chat")
            .putInt("${prefix}_context", contextLength)
            .putStringSet("${prefix}_caps", setOf("chat"))
            .putString("${prefix}_provider", "同名提供商")
            .putString("${prefix}_provider_id", "default")
            .putString("${prefix}_remote_model_id", "deepseek-v4-flash")
            .putInt("${prefix}_maxoutput", 0)
            .commit()
    }

    private fun persistLegacyModel(
        remoteModelId: String,
        name: String,
        type: String,
        contextLength: Int,
        capabilities: Set<String>,
        maxOutputTokens: Int,
    ) {
        val id = "default::$remoteModelId"
        val prefix = "model_info_$id"
        app.getSharedPreferences("nexara_settings", 0).edit()
            .clear()
            .putStringSet("all_models", setOf(id))
            .putStringSet("enabled_models", setOf(id))
            .putString("all_models_order", id)
            .putString("${prefix}_name", name)
            .putString("${prefix}_type", type)
            .putInt("${prefix}_context", contextLength)
            .putStringSet("${prefix}_caps", capabilities)
            .putString("${prefix}_provider", "同名提供商")
            .putString("${prefix}_provider_id", "default")
            .putString("${prefix}_remote_model_id", remoteModelId)
            .putInt("${prefix}_maxoutput", maxOutputTokens)
            .commit()
    }

    private fun <T> withBundledCatalog(block: () -> T): T {
        val snapshot = listOf(
            File("app/src/main/assets/model-catalog/models-dev.normalized.json"),
            File("src/main/assets/model-catalog/models-dev.normalized.json"),
            File("native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json"),
        ).firstOrNull(File::isFile)
            ?: error("models-dev.normalized.json fixture is unavailable")
        val catalog = BundledModelCatalog.fromJson(snapshot.readText())
        return ModelCatalogRuntime.withTestResolver(
            replacement = ModelCatalogRuntime.resolverFor(catalog),
            block = block,
        )
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
