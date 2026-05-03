package com.promenar.nexara.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmProviderTest {

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
            val builder = LlmProvider.builder()
                .protocolId(ProtocolId.ANTHROPIC)
                .baseUrl("https://api.anthropic.com")
                .apiKey("test-key")
                .model("claude-3-5-sonnet")

            val ex = assertThrows<NotImplementedError> { builder.build() }
            assertThat(ex.message).contains("Anthropic")
        }

        @Test
        fun `builder with vertex AI protocol`() {
            val builder = LlmProvider.builder()
                .protocolId(ProtocolId.VERTEX_AI)
                .baseUrl("https://us-central1-aiplatform.googleapis.com")
                .apiKey("test-key")
                .model("gemini-3-flash-preview")

            val ex = assertThrows<NotImplementedError> { builder.build() }
            assertThat(ex.message).contains("Vertex")
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
        fun `anthropic and vertex_ai protocols throw NotImplementedError`() {
            for (id in listOf(ProtocolId.ANTHROPIC, ProtocolId.VERTEX_AI)) {
                val builder = LlmProvider.builder()
                    .protocolId(id)
                    .baseUrl("https://example.com")
                    .apiKey("key")
                    .model("model")

                assertThrows<NotImplementedError> { builder.build() }
            }
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
