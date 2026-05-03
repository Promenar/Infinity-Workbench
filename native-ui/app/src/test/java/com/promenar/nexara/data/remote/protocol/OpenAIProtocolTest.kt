package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class LlmTestConfig(
    val providers: LlmTestProviders
)

@Serializable
data class LlmTestProviders(
    @SerialName("openai_compatible")
    val openaiCompatible: LlmTestOpenAiCompatible,
    @SerialName("anthropic_compatible")
    val anthropicCompatible: LlmTestAnthropicCompatible,
    val vertexai: LlmTestVertexAi
)

@Serializable
data class LlmTestOpenAiCompatible(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = ""
)

@Serializable
data class LlmTestAnthropicCompatible(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = ""
)

@Serializable
data class LlmTestVertexAi(
    val name: String = "",
    val serviceAccountKeyPath: String = "",
    val projectId: String = "",
    val location: String = "",
    val modelId: String = ""
)

class OpenAIProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var config: LlmTestOpenAiCompatible

    @BeforeEach
    fun setUp() {
        val configFile = File("test-resources/llm-test-config.json")
        Assumptions.assumeTrue(configFile.exists(), "Test config file not found at test-resources/llm-test-config.json")

        val testConfig = json.decodeFromString<LlmTestConfig>(configFile.readText())
        config = testConfig.providers.openaiCompatible

        Assumptions.assumeTrue(
            config.apiKey.isNotBlank()
                && !config.apiKey.startsWith("YOUR_")
                && config.apiKey != "placeholder",
            "OpenAI compatible API key not configured"
        )
        Assumptions.assumeTrue(
            config.baseUrl.isNotBlank(),
            "OpenAI compatible base URL not configured"
        )
    }

    @Nested
    @DisplayName("Streaming (sendPrompt)")
    inner class StreamingTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("streaming returns at least 1 TextDelta and a Done signal")
        fun streamingReturnsTextDeltaAndDone() {
            runBlocking<Unit> {
                val protocol = OpenAIProtocol(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.modelId
                )

                val request = PromptRequest(
                    messages = listOf(ProtocolMessage(role = "user", content = "Say hello in one word")),
                    model = config.modelId,
                    maxTokens = 50,
                    stream = true
                )

                val chunks: List<StreamChunk> = protocol.sendPrompt(request).toList()

                val textDeltas = chunks.filterIsInstance<StreamChunk.TextDelta>()
                val doneChunks = chunks.filterIsInstance<StreamChunk.Done>()
                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()

                assertThat(errorChunks).isEmpty()
                assertThat(textDeltas).isNotEmpty()
                assertThat(doneChunks).hasSize(1)

                val fullText = textDeltas.joinToString("") { it.content }
                assertThat(fullText).isNotEmpty()
            }
        }
    }

    @Nested
    @DisplayName("Non-streaming (sendPromptSync)")
    inner class SyncTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("sync returns a PromptResponse with content")
        fun syncReturnsPromptResponse() {
            runBlocking<Unit> {
                val protocol = OpenAIProtocol(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.modelId
                )

                val request = PromptRequest(
                    messages = listOf(ProtocolMessage(role = "user", content = "Say hello in one word")),
                    model = config.modelId,
                    maxTokens = 50,
                    stream = false
                )

                val response = protocol.sendPromptSync(request)

                assertThat(response.content.isNotEmpty() || !response.reasoning.isNullOrEmpty()).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("Error handling")
    inner class ErrorTests {

        @Test
        @Timeout(value = 15, unit = TimeUnit.SECONDS)
        @DisplayName("invalid API key produces Error chunk")
        fun invalidApiKeyProducesError() {
            runBlocking<Unit> {
                val protocol = OpenAIProtocol(
                    baseUrl = config.baseUrl,
                    apiKey = "sk-invalid-key-000000000000000000000000",
                    model = config.modelId
                )

                val request = PromptRequest(
                    messages = listOf(ProtocolMessage(role = "user", content = "test")),
                    model = config.modelId,
                    maxTokens = 5
                )

                val chunks: List<StreamChunk> = protocol.sendPrompt(request).toList()
                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()

                assertThat(errorChunks).isNotEmpty()
            }
        }
    }
}
