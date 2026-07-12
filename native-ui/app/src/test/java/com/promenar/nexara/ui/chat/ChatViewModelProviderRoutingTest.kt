package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedLlmClient
import org.junit.Test
import java.io.File

class ChatViewModelProviderRoutingTest {
    @Test
    fun `解析失败时不创建客户端且暴露 typed 恢复信息`() {
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

        val result = ChatProviderRouteGate(router).resolve("provider::model")

        assertThat(result.failure).isEqualTo(failure)
        assertThat(result.client).isNull()
        assertThat(clientCreations).isEqualTo(0)
    }

    @Test
    fun `ChatViewModel 在上下文网络路径前执行请求级解析`() {
        val source = sequenceOf(
            File("src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt"),
        ).first { it.isFile }.readText()
        val resolveIndex = source.indexOf("providerRouteGate?.resolve(effectiveModel)")
        val contextIndex = source.indexOf("contextBuilder.buildContext(contextParams)")

        assertThat(resolveIndex).isAtLeast(0)
        assertThat(contextIndex).isGreaterThan(resolveIndex)
    }
}
