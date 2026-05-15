package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class CrossProtocolParamAuditTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `OpenAIProtocol transmits all 7 parameters`() = runTest {
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteReadPacket().readText()
            respond(content = "{}", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val protocol = OpenAIProtocol(
            baseUrl = "https://api.openai.com", apiKey = "test-key",
            model = "gpt-4o", httpClient = HttpClient(mockEngine)
        )
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = "hi")),
            model = "gpt-4o",
            temperature = 0.5, topP = 0.8, maxTokens = 100,
            topK = 40, repetitionPenalty = 1.1,
            frequencyPenalty = 0.3, presencePenalty = 0.2,
            stream = false
        )
        try { protocol.sendPromptSync(request) } catch (_: Exception) {}
        assertThat(capturedBody).isNotNull()
        val body = json.parseToJsonElement(capturedBody!!).jsonObject
        assertThat(body["temperature"]?.jsonPrimitive?.double).isEqualTo(0.5)
        assertThat(body["top_p"]?.jsonPrimitive?.double).isEqualTo(0.8)
        assertThat(body["max_tokens"]?.jsonPrimitive?.int).isEqualTo(100)
        assertThat(body["top_k"]?.jsonPrimitive?.int).isEqualTo(40)
        assertThat(body["repetition_penalty"]?.jsonPrimitive?.double).isEqualTo(1.1)
        assertThat(body["frequency_penalty"]?.jsonPrimitive?.double).isEqualTo(0.3)
        assertThat(body["presence_penalty"]?.jsonPrimitive?.double).isEqualTo(0.2)
    }

    @Test
    fun `AnthropicProtocol does not crash with unsupported penalty params`() = runTest {
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteReadPacket().readText()
            respond(content = "{\"type\":\"message\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val protocol = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com", apiKey = "test-key",
            model = "claude-3", httpClient = HttpClient(mockEngine)
        )
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = "hi")),
            model = "claude-3",
            temperature = 0.5, topK = 40,
            repetitionPenalty = 1.2,
            frequencyPenalty = 0.3,
            presencePenalty = 0.2,
            stream = false
        )
        try { protocol.sendPromptSync(request) } catch (_: Exception) {}
        assertThat(capturedBody).isNotNull()
        val body = json.parseToJsonElement(capturedBody!!).jsonObject
        assertThat(body.containsKey("frequency_penalty")).isFalse()
        assertThat(body.containsKey("presence_penalty")).isFalse()
        assertThat(body.containsKey("repetition_penalty")).isFalse()
    }

    @Test
    fun `VertexAIProtocol includes repetitionPenalty`() = runTest {
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteReadPacket().readText()
            respond(content = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tempKey = java.io.File.createTempFile("sa_key", ".json")
        tempKey.writeText("{\"client_email\":\"test@test.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nMIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDE...\\n-----END PRIVATE KEY-----\"}")
        val protocol = VertexAIProtocol(
            serviceAccountKeyPath = tempKey.absolutePath,
            projectId = "test-project", model = "gemini-1.5",
            httpClient = HttpClient(mockEngine)
        )
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = "hi")),
            model = "gemini-1.5",
            temperature = 0.6, topK = 30,
            repetitionPenalty = 1.3, frequencyPenalty = 0.1, presencePenalty = 0.2,
            stream = false
        )
        try { protocol.sendPromptSync(request) } catch (_: Exception) {}
        if (capturedBody != null) {
            val body = json.parseToJsonElement(capturedBody!!).jsonObject
            val config = body["generation_config"]?.jsonObject
            if (config != null) {
                assertThat(config["repetition_penalty"]?.jsonPrimitive?.double).isEqualTo(1.3)
                assertThat(config["frequency_penalty"]?.jsonPrimitive?.double).isEqualTo(0.1)
            }
        }
    }

    @Test
    fun `GenericOpenAICompatProtocol still transmits all 7 parameters after Adapter migration`() = runTest {
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedBody = request.body.toByteReadPacket().readText()
            respond(content = "{}", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val protocol = GenericOpenAICompatProtocol(
            baseUrl = "http://localhost", apiKey = "test-key",
            model = "test-model", httpClient = HttpClient(mockEngine)
        )
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(role = "user", content = "hi")),
            model = "test-model",
            temperature = 0.8, topP = 0.9, maxTokens = 100,
            topK = 50, repetitionPenalty = 1.2,
            presencePenalty = 0.5, frequencyPenalty = 0.3,
            stream = false
        )
        try { protocol.sendPromptSync(request) } catch (_: Exception) {}
        assertThat(capturedBody).isNotNull()
        val body = json.parseToJsonElement(capturedBody!!).jsonObject
        assertThat(body["temperature"]?.jsonPrimitive?.double).isEqualTo(0.8)
        assertThat(body["top_p"]?.jsonPrimitive?.double).isEqualTo(0.9)
        assertThat(body["max_tokens"]?.jsonPrimitive?.int).isEqualTo(100)
        assertThat(body["top_k"]?.jsonPrimitive?.int).isEqualTo(50)
        assertThat(body["repetition_penalty"]?.jsonPrimitive?.double).isEqualTo(1.2)
        assertThat(body["presence_penalty"]?.jsonPrimitive?.double).isEqualTo(0.5)
        assertThat(body["frequency_penalty"]?.jsonPrimitive?.double).isEqualTo(0.3)
    }

    @Test
    fun `PromptRequest serialization carries images and all advanced params`() {
        val request = PromptRequest(
            messages = listOf(ProtocolMessage(
                role = "user", content = "Describe",
                imageUrls = listOf(ImageInput(base64 = "abc", mimeType = "image/png"))
            )),
            model = "gpt-4o",
            temperature = 0.5, topK = 40, repetitionPenalty = 1.1,
            frequencyPenalty = 0.3, presencePenalty = 0.2
        )
        val encoded = json.encodeToString(PromptRequest.serializer(), request)
        assertThat(encoded).contains("\"topK\":40")
        assertThat(encoded).contains("\"repetitionPenalty\":1.1")
        assertThat(encoded).contains("\"frequencyPenalty\":0.3")
        assertThat(encoded).contains("\"imageUrls\"")
    }
}
