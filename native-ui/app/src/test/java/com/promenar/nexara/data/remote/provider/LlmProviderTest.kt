package com.promenar.nexara.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.UnsupportedProviderProtocolException
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.StreamChunk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LlmProviderTest {

    @Test
    fun `resolving provider only resolves real protocol at each request boundary`() = runTest {
        var resolveCount = 0
        val delegate = mockk<LlmProtocol> {
            every { protocolType } returns ProtocolId.OPENAI
            coEvery { sendPrompt(any()) } returns flowOf(StreamChunk.Done)
            every { cancel() } returns Unit
        }
        val provider = LlmProvider.resolving(ProtocolId.OPENAI) {
            resolveCount++
            delegate
        }

        assertThat(resolveCount).isEqualTo(0)
        provider.sendPrompt(PromptRequest(emptyList(), "fake-model")).toList()
        provider.sendPrompt(PromptRequest(emptyList(), "fake-model")).toList()

        assertThat(resolveCount).isEqualTo(2)
    }

    @Nested
    @DisplayName("Builder")
    inner class BuilderTests {

        @Test
        fun `builder creates instance with default protocol`() {
            val provider = LlmProvider.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey("test-key")
                .model("gpt-4o")
                .build()

            assertThat(provider.protocolId).isEqualTo(ProtocolId.OPENAI)
        }

        @Test
        fun `builder with anthropic protocol`() {
            val provider = LlmProvider.builder()
                .protocolId(ProtocolId.ANTHROPIC)
                .baseUrl("https://api.anthropic.com")
                .apiKey("test-key")
                .model("claude-3-5-sonnet")
                .build()

            assertThat(provider.protocolId).isEqualTo(ProtocolId.ANTHROPIC)
        }

        @Test
        fun `builder with vertex AI protocol`() {
            val provider = LlmProvider.builder()
                .protocolId(ProtocolId.VERTEX_AI)
                .serviceAccountJson("{\"client_email\":\"fake@example.invalid\",\"private_key\":\"fake-private-key\"}")
                .projectId("test-project")
                .location("us-central1")
                .model("gemini-3-flash-preview")
                .build()

            assertThat(provider.protocolId).isEqualTo(ProtocolId.VERTEX_AI)
        }

        @Test
        fun `builder methods are chainable`() {
            val builder = LlmProvider.builder()
                .protocolId(ProtocolId.OPENAI)
                .baseUrl("https://api.example.com/v1")
                .apiKey("key")
                .model("model-1")

            assertThat(builder).isNotNull()
        }
    }

    @Nested
    @DisplayName("ProtocolId routing")
    inner class ProtocolIdTests {

        @Test
        fun `两套 factory 为所有 OpenAI 兼容预设保留实际协议身份`() {
            val protocols = listOf(
                ProtocolType.DeepSeek,
                ProtocolType.Moonshot_Kimi,
                ProtocolType.Qwen_DashScope,
                ProtocolType.Zhipu_GLM,
                ProtocolType.Doubao_ByteDance,
                ProtocolType.Baichuan,
                ProtocolType.Mistral_Chat,
            )

            protocols.forEach { type ->
                val fromProtocolFactory = ProtocolFactory.create(
                    type = type,
                    baseUrl = type.defaultBaseUrl,
                    apiKey = "fake-key",
                    model = "fake-model",
                )
                val fromProviderBuilder = LlmProvider.builder()
                    .protocolType(type)
                    .baseUrl(type.defaultBaseUrl)
                    .apiKey("fake-key")
                    .model("fake-model")
                    .build()

                assertThat(fromProtocolFactory.protocolType).isEqualTo(type)
                assertThat(fromProviderBuilder.protocolType).isEqualTo(type)
            }
        }

        @Test
        fun `历史 Cohere 与 Yi 配置可解码但两套 factory 都明确拒绝构造`() {
            val historical = listOf(ProtocolType.Cohere_Chat, ProtocolType.Yi_ZeroOne)

            historical.forEach { type ->
                assertThat(ProtocolType.fromLegacyName(type::class.simpleName.orEmpty()))
                    .isEqualTo(type)

                val protocolFactoryFailure = runCatching {
                    ProtocolFactory.create(
                        type = type,
                        baseUrl = type.defaultBaseUrl,
                        apiKey = "fake-key",
                        model = "fake-model",
                    )
                }.exceptionOrNull()
                val providerFactoryFailure = runCatching {
                    LlmProvider.builder()
                        .protocolType(type)
                        .baseUrl(type.defaultBaseUrl)
                        .apiKey("fake-key")
                        .model("fake-model")
                        .build()
                }.exceptionOrNull()

                assertThat(protocolFactoryFailure).isInstanceOf(UnsupportedProviderProtocolException::class.java)
                assertThat(providerFactoryFailure).isInstanceOf(UnsupportedProviderProtocolException::class.java)
            }
        }

        @Test
        fun `all protocol ids build successfully`() {
            val openai = LlmProvider.builder()
                .protocolId(ProtocolId.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .apiKey("key")
                .model("gpt-4o")
                .build()
            assertThat(openai.protocolId).isEqualTo(ProtocolId.OPENAI)

            val anthropic = LlmProvider.builder()
                .protocolId(ProtocolId.ANTHROPIC)
                .baseUrl("https://api.anthropic.com")
                .apiKey("key")
                .model("claude-3-5-sonnet")
                .build()
            assertThat(anthropic.protocolId).isEqualTo(ProtocolId.ANTHROPIC)

            val vertexai = LlmProvider.builder()
                .protocolId(ProtocolId.VERTEX_AI)
                .serviceAccountJson("{\"client_email\":\"fake@example.invalid\",\"private_key\":\"fake-private-key\"}")
                .projectId("test-project")
                .model("gemini-3-flash-preview")
                .build()
            assertThat(vertexai.protocolId).isEqualTo(ProtocolId.VERTEX_AI)
        }

        @Test
        fun `openai protocol builds successfully`() {
            val provider = LlmProvider.builder()
                .protocolId(ProtocolId.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .apiKey("key")
                .model("gpt-4o")
                .build()

            assertThat(provider.protocolId).isEqualTo(ProtocolId.OPENAI)
        }
    }
}
