package com.promenar.nexara.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Test

class ProviderRequestRouterTest {
    private val models = linkedMapOf<String, ModelInfo>()
    private val providers = linkedMapOf<String, ProviderListItem>()
    private val configs = linkedMapOf<String, ProviderConfig>()
    private val createdConfigs = mutableListOf<UnifiedProviderConfig>()

    private val router = DefaultProviderRequestRouter(
        modelResolver = models::get,
        providerResolver = providers::get,
        configResolver = configs::get,
        clientFactory = { config ->
            createdConfigs += config
            UnifiedLlmClient(providerConfigResolver = { config })
        },
    )

    @Test
    fun `两个 Provider 的同名远端模型按复合标识独立解析`() {
        seed("provider-a", "same-model", "key-a")
        seed("provider-b", "same-model", "key-b")

        val a = router.resolve("provider-a::same-model") as ProviderResolution.Success
        val b = router.resolve("provider-b::same-model") as ProviderResolution.Success
        router.createClient(a.value)
        router.createClient(b.value)

        assertThat(a.value.remoteModelId).isEqualTo("same-model")
        assertThat(b.value.remoteModelId).isEqualTo("same-model")
        assertThat(createdConfigs.map { it.apiKey }).containsExactly("key-a", "key-b").inOrder()
    }

    @Test
    fun `Provider 删除后旧模型不会回退到其他 Provider`() {
        seed("deleted", "model", "key")
        providers.remove("deleted")
        configs.remove("deleted")

        assertFailure("deleted::model", ProviderResolutionError.PROVIDER_NOT_FOUND)
    }

    @Test
    fun `缺少 Key 在创建客户端之前失败`() {
        seed("p", "model", "")

        assertFailure("p::model", ProviderResolutionError.API_KEY_MISSING)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `Provider 列表与配置协议不一致时失败`() {
        seed("p", "model", "key")
        configs["p"] = configs.getValue("p").copy(protocolType = ProtocolType.Anthropic_Messages)

        assertFailure("p::model", ProviderResolutionError.PROTOCOL_MISMATCH)
    }

    @Test
    fun `云端非 HTTPS endpoint 在网络前失败`() {
        seed("p", "model", "key", baseUrl = "http://insecure.invalid")

        assertFailure("p::model", ProviderResolutionError.BASE_URL_INVALID)
        assertThat(createdConfigs).isEmpty()
    }

    @Test
    fun `模型复合 ID 与归属不一致时失败`() {
        seed("p", "model", "key")
        models["other::model"] = model("p", "model").copy(id = "other::model")

        assertFailure("other::model", ProviderResolutionError.MODEL_PROVIDER_MISMATCH)
    }

    @Test
    fun `已解析请求冻结当时配置而下一次解析读取新配置`() {
        seed("p", "model", "old-key")
        val first = (router.resolve("p::model") as ProviderResolution.Success).value
        configs["p"] = configs.getValue("p").copy(apiKey = "new-key")
        val second = (router.resolve("p::model") as ProviderResolution.Success).value

        router.createClient(first)
        router.createClient(second)

        assertThat(createdConfigs.map { it.apiKey }).containsExactly("old-key", "new-key").inOrder()
    }

    @Test
    fun `禁用 Provider 在网络前失败`() {
        seed("p", "model", "key")
        providers["p"] = providers.getValue("p").copy(enabled = false)

        assertFailure("p::model", ProviderResolutionError.PROVIDER_DISABLED)
    }

    @Test
    fun `本地协议允许空 endpoint 与空 Key`() {
        val providerId = "local"
        providers[providerId] = ProviderListItem(
            id = providerId,
            name = "本地",
            protocolType = ProtocolType.Local,
        )
        configs[providerId] = ProviderConfig(
            protocolType = ProtocolType.Local,
            model = "local-model",
        )
        models["local::local-model"] = model(providerId, "local-model")

        val result = router.resolve("local::local-model") as ProviderResolution.Success

        assertThat(result.value.config.protocolType).isEqualTo(ProtocolType.Local)
        assertThat(result.value.remoteModelId).isEqualTo("local-model")
    }

    private fun seed(providerId: String, remoteModelId: String, key: String, baseUrl: String = "https://$providerId.invalid") {
        providers[providerId] = ProviderListItem(
            id = providerId,
            name = providerId,
            protocolType = ProtocolType.OpenAI_ChatCompletions,
        )
        configs[providerId] = ProviderConfig(
            protocolType = ProtocolType.OpenAI_ChatCompletions,
            baseUrl = baseUrl,
            apiKey = key,
            model = remoteModelId,
        )
        models[stableModelId(providerId, remoteModelId)] = model(providerId, remoteModelId)
    }

    private fun model(providerId: String, remoteModelId: String) = ModelInfo(
        name = remoteModelId,
        id = stableModelId(providerId, remoteModelId),
        remoteModelId = remoteModelId,
        description = "",
        enabled = true,
        providerName = providerId,
        providerId = providerId,
    )

    private fun assertFailure(modelId: String, reason: ProviderResolutionError) {
        val failure = router.resolve(modelId) as ProviderResolution.Failure
        assertThat(failure.reason).isEqualTo(reason)
    }
}
