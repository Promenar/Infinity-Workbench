package com.promenar.nexara.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
                .serviceAccountKeyPath("/dummy/path.json")
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
                .serviceAccountKeyPath("/dummy/path.json")
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
