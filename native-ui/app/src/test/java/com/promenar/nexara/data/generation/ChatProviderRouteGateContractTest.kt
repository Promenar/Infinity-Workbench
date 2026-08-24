package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.protocol.ProtocolType
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ChatProviderRouteGateContractTest {
    @Test
    fun `路由准备失败字符串化不得泄漏模型与provider标识`() {
        val result = ChatRoutePreparation.Failure(
            ProviderResolution.Failure(
                ProviderResolutionError.MODEL_NOT_FOUND,
                "private-model-marker",
                "private-provider-marker",
            ),
        )

        assertThat(result.toString()).doesNotContain("private-model-marker")
        assertThat(result.toString()).doesNotContain("private-provider-marker")
        assertThat(result.toString()).contains("MODEL_NOT_FOUND")
    }

    @Test
    fun `Vertex 路由在 thought signature 闭环前关闭工具能力`() {
        val resolved = ResolvedProviderModel(
            modelId = "vertex::gemini",
            remoteModelId = "gemini",
            providerId = "vertex",
            providerName = "Vertex",
            config = UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini",
            ),
        )
        val router = mockk<ProviderRequestRouter>()
        every { router.resolve("vertex::gemini") } returns ProviderResolution.Success(resolved)
        every { router.createClient(resolved) } returns mockk<UnifiedLlmClient>()

        val route = ChatProviderRouteGate(router).resolve("vertex::gemini")

        assertThat(route.supportsToolCalls).isFalse()
    }
}
