package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.generation.ChatProviderRouteGate
import com.promenar.infinity.data.generation.ChatRoutePreparation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelProviderRoutingTest {
    @Test
    fun `解析失败时不创建客户端且暴露 typed 恢复信息`() = runTest {
        var clientCreations = 0
        val failure = ProviderResolution.Failure(
            ProviderResolutionError.API_KEY_MISSING,
            "provider::model",
            "provider",
        )
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = failure
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
                clientCreations++
                error("失败路由不得创建网络客户端")
            }
        }

        var contextCalls = 0
        val result = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)).prepare("provider::model") {
            contextCalls++
            "context"
        }

        assertThat(result).isEqualTo(ChatRoutePreparation.Failure(failure))
        assertThat(contextCalls).isEqualTo(0)
        assertThat(clientCreations).isEqualTo(0)
    }

    @Test
    fun `成功路由先构建当次客户端再把远端模型交给发送阶段`() = runTest {
        var currentKey = "key-v1"
        val clientKeys = mutableListOf<String>()
        var contextCalls = 0
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId = modelId,
                    remoteModelId = "remote-model",
                    providerId = "provider",
                    providerName = "Provider",
                    config = UnifiedProviderConfig(
                        protocolType = ProtocolType.OpenAI_ChatCompletions,
                        baseUrl = "https://provider.invalid",
                        apiKey = currentKey,
                        defaultModel = "remote-model",
                    ),
                )
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient {
                clientKeys += resolved.config.apiKey
                return UnifiedLlmClient(providerConfigResolver = { resolved.config })
            }
        }
        val gate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler))

        val first = gate.prepare("provider::remote-model") {
            contextCalls++
            "context-v1"
        } as ChatRoutePreparation.Success<String>
        currentKey = "key-v2"
        val second = gate.prepare("provider::remote-model") {
            contextCalls++
            "context-v2"
        } as ChatRoutePreparation.Success<String>

        assertThat(first.route.remoteModelId).isEqualTo("remote-model")
        assertThat(first.context).isEqualTo("context-v1")
        assertThat(second.context).isEqualTo("context-v2")
        assertThat(contextCalls).isEqualTo(2)
        assertThat(clientKeys).containsExactly("key-v1", "key-v2").inOrder()
    }
}
