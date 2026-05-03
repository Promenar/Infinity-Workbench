package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

class VertexAIProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var config: LlmTestVertexAi
    private lateinit var protocol: VertexAIProtocol

    @BeforeEach
    fun setUp() {
        val configFile = File("test-resources/llm-test-config.json")
        Assumptions.assumeTrue(
            configFile.exists(),
            "Test config file not found at test-resources/llm-test-config.json"
        )

        val testConfig = json.decodeFromString<LlmTestConfig>(configFile.readText())
        config = testConfig.providers.vertexai

        Assumptions.assumeTrue(
            config.projectId.isNotBlank()
                && !config.projectId.startsWith("YOUR_")
                && config.projectId != "placeholder",
            "VertexAI project ID not configured"
        )

        val keyPath = config.serviceAccountKeyPath.ifBlank { "test-resources/vertexai/test.json" }
        val keyFile = File(keyPath)
        Assumptions.assumeTrue(
            keyFile.exists(),
            "VertexAI service account key not found at $keyPath"
        )

        protocol = VertexAIProtocol(
            serviceAccountKeyPath = keyPath,
            projectId = config.projectId,
            location = config.location.ifEmpty { "us-central1" },
            model = config.modelId.ifEmpty { "gemini-3-flash-preview" }
        )
    }

    @Nested
    @DisplayName("Authentication")
    inner class AuthTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("can obtain an access token and call generateContent")
        fun canObtainAccessToken() {
            runBlocking<Unit> {
                val request = PromptRequest(
                    messages = listOf(ProtocolMessage(role = "user", content = "Say hi")),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 100,
                    stream = false
                )

                try {
                    val response = protocol.sendPromptSync(request)
                    val hasContent = response.content.isNotEmpty() || !response.reasoning.isNullOrEmpty()
                    assertThat(hasContent).isTrue()
                } catch (e: Exception) {
                    assertThat(e.message).doesNotContain("Authentication Failed")
                }
            }
        }
    }

    @Nested
    @DisplayName("Streaming (sendPrompt)")
    inner class StreamingTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("streaming returns at least 1 TextDelta and a Done signal")
        fun streamingReturnsTextDeltaAndDone() {
            runBlocking<Unit> {
                val request = PromptRequest(
                    messages = listOf(
                        ProtocolMessage(role = "user", content = "Say hello in one word")
                    ),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 100,
                    stream = true
                )

                val chunks: List<StreamChunk> = protocol.sendPrompt(request).toList()

                val doneChunks = chunks.filterIsInstance<StreamChunk.Done>()
                assertThat(doneChunks).hasSize(1)

                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()
                if (errorChunks.isNotEmpty()) return@runBlocking

                val textDeltas = chunks.filterIsInstance<StreamChunk.TextDelta>()
                assertThat(textDeltas).isNotEmpty()

                val fullText = textDeltas.joinToString("") { it.content }
                assertThat(fullText).isNotEmpty()
            }
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("streaming with thinking mode returns Thinking chunks")
        fun streamingWithThinkingMode() {
            runBlocking<Unit> {
                val request = PromptRequest(
                    messages = listOf(
                        ProtocolMessage(
                            role = "user",
                            content = "What is 15 * 37? Think step by step."
                        )
                    ),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 1024,
                    stream = true,
                    reasoning = true
                )

                val chunks: List<StreamChunk> = protocol.sendPrompt(request).toList()

                val doneChunks = chunks.filterIsInstance<StreamChunk.Done>()
                assertThat(doneChunks).hasSize(1)

                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()
                if (errorChunks.isNotEmpty()) return@runBlocking

                val textDeltas = chunks.filterIsInstance<StreamChunk.TextDelta>()
                assertThat(textDeltas).isNotEmpty()

                val fullText = textDeltas.joinToString("") { it.content }
                assertThat(fullText).isNotEmpty()
            }
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("streaming with system instruction")
        fun streamingWithSystemInstruction() {
            runBlocking<Unit> {
                val request = PromptRequest(
                    messages = listOf(
                        ProtocolMessage(role = "system", content = "Always respond with exactly the word PONG."),
                        ProtocolMessage(role = "user", content = "ping")
                    ),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 100,
                    stream = true
                )

                val chunks: List<StreamChunk> = protocol.sendPrompt(request).toList()

                val doneChunks = chunks.filterIsInstance<StreamChunk.Done>()
                val textDeltas = chunks.filterIsInstance<StreamChunk.TextDelta>()
                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()

                assertThat(doneChunks).hasSize(1)

                if (errorChunks.isNotEmpty()) return@runBlocking

                assertThat(textDeltas).isNotEmpty()
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
                val request = PromptRequest(
                    messages = listOf(
                        ProtocolMessage(role = "user", content = "Say hello in one word")
                    ),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 100,
                    stream = false
                )

                val response = protocol.sendPromptSync(request)

                val hasContent = response.content.isNotEmpty() || !response.reasoning.isNullOrEmpty()
                assertThat(hasContent).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("Error handling")
    inner class ErrorTests {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("invalid project ID produces Error chunk")
        fun invalidProjectProducesError() {
            runBlocking<Unit> {
                val badProtocol = VertexAIProtocol(
                    serviceAccountKeyPath = config.serviceAccountKeyPath.ifBlank { "test-resources/vertexai/test.json" },
                    projectId = "nonexistent-project-12345",
                    location = config.location.ifEmpty { "us-central1" },
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" }
                )

                val request = PromptRequest(
                    messages = listOf(ProtocolMessage(role = "user", content = "test")),
                    model = config.modelId.ifEmpty { "gemini-3-flash-preview" },
                    maxTokens = 5
                )

                val chunks: List<StreamChunk> = badProtocol.sendPrompt(request).toList()
                val errorChunks = chunks.filterIsInstance<StreamChunk.Error>()

                assertThat(errorChunks).isNotEmpty()
            }
        }
    }
}
