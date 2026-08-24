package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderWireEndpointTest {
    private val request = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = "ping")),
        model = "fake-model",
        stream = false,
    )

    @Test
    fun `OpenAI generation and models use resolver exact URLs`() = runTest {
        val urls = mutableListOf<String>()
        val client = recordingClient(urls)
        val protocol = OpenAIProtocol(
            baseUrl = "https://api.openai.com",
            apiKey = "fake-key",
            model = "fake-model",
            httpClient = client,
        )

        runCatching { protocol.sendPromptSync(request) }
        protocol.listModels()

        assertThat(urls).containsExactly(
            "https://api.openai.com/v1/chat/completions",
            "https://api.openai.com/v1/models",
        ).inOrder()
    }

    @Test
    fun `OpenAI Responses generation and models use resolver exact URLs`() = runTest {
        val urls = mutableListOf<String>()
        val protocol = OpenAIResponsesProtocol(
            baseUrl = "https://api.openai.com",
            apiKey = "fake-key",
            model = "fake-model",
            httpClient = recordingClient(urls),
        )

        runCatching { protocol.sendPromptSync(request) }
        protocol.listModels()

        assertThat(urls).containsExactly(
            "https://api.openai.com/v1/responses",
            "https://api.openai.com/v1/models",
        ).inOrder()
    }

    @Test
    fun `完整 custom 和 OpenAI v2 inference URL 在真实 wire 保持原样`() = runTest {
        val genericUrls = mutableListOf<String>()
        val generic = GenericOpenAICompatProtocol(
            baseUrl = "https://generic.example.invalid/chat/completions",
            apiKey = "fake-key",
            model = "fake-model",
            protocolType = ProtocolType.Generic_OpenAI_Compat,
            httpClient = recordingClient(genericUrls),
        )
        val openAiUrls = mutableListOf<String>()
        val openAi = OpenAIProtocol(
            baseUrl = "https://proxy.example.invalid/v2/chat/completions",
            apiKey = "fake-key",
            model = "fake-model",
            httpClient = recordingClient(openAiUrls),
        )

        runCatching { generic.sendPromptSync(request) }
        runCatching { openAi.sendPromptSync(request) }

        assertThat(genericUrls).containsExactly(
            "https://generic.example.invalid/chat/completions",
        )
        assertThat(openAiUrls).containsExactly(
            "https://proxy.example.invalid/v2/chat/completions",
        )
    }

    @Test
    fun `Anthropic generation uses resolver exact URL and headers`() = runTest {
        var apiKeyHeader: String? = null
        var versionHeader: String? = null
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { captured ->
            urls += captured.url.toString()
            apiKeyHeader = captured.headers["x-api-key"]
            versionHeader = captured.headers["anthropic-version"]
            respond(
                content = "{}",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val protocol = AnthropicProtocol(
            baseUrl = "https://api.anthropic.com",
            apiKey = "fake-key",
            model = "fake-model",
            httpClient = client,
        )

        runCatching { protocol.sendPromptSync(request) }

        assertThat(urls).containsExactly("https://api.anthropic.com/v1/messages")
        assertThat(apiKeyHeader).isEqualTo("fake-key")
        assertThat(versionHeader).isEqualTo("2023-06-01")
    }

    @Test
    fun `OpenAI compatible advertised presets use exact inference URLs`() = runTest {
        val cases = listOf(
            ProtocolType.DeepSeek to "https://api.deepseek.com/chat/completions",
            ProtocolType.Moonshot_Kimi to "https://api.moonshot.cn/v1/chat/completions",
            ProtocolType.Qwen_DashScope to "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            ProtocolType.Zhipu_GLM to "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ProtocolType.Doubao_ByteDance to "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            ProtocolType.Baichuan to "https://api.baichuan-ai.com/v1/chat/completions",
            ProtocolType.Mistral_Chat to "https://api.mistral.ai/v1/chat/completions",
        )

        cases.forEach { (type, inferenceUrl) ->
            val urls = mutableListOf<String>()
            val protocol = GenericOpenAICompatProtocol(
                baseUrl = type.defaultBaseUrl,
                apiKey = "fake-key",
                model = "fake-model",
                protocolType = type,
                httpClient = recordingClient(urls),
            )

            runCatching { protocol.sendPromptSync(request) }

            assertThat(protocol.protocolType).isEqualTo(type)
            assertThat(urls).containsExactly(inferenceUrl)
        }
    }

    @Test
    fun `only adapted model list protocols emit authority backed URLs`() = runTest {
        val cases = listOf(
            ProtocolType.DeepSeek to "https://api.deepseek.com/models",
            ProtocolType.Mistral_Chat to "https://api.mistral.ai/v1/models",
        )

        cases.forEach { (type, modelsUrl) ->
            val urls = mutableListOf<String>()
            val protocol = GenericOpenAICompatProtocol(
                baseUrl = type.defaultBaseUrl,
                apiKey = "fake-key",
                model = "fake-model",
                protocolType = type,
                httpClient = recordingClient(urls),
            )

            protocol.listModels()

            assertThat(urls).containsExactly(modelsUrl)
        }
    }

    @Test
    fun `unsupported dynamic model lists fail before HTTP`() = runTest {
        val unsupported = listOf(
            ProtocolType.Moonshot_Kimi,
            ProtocolType.Qwen_DashScope,
            ProtocolType.Zhipu_GLM,
            ProtocolType.Doubao_ByteDance,
            ProtocolType.Baichuan,
        )

        unsupported.forEach { type ->
            val urls = mutableListOf<String>()
            val protocol = GenericOpenAICompatProtocol(
                baseUrl = type.defaultBaseUrl,
                apiKey = "fake-key",
                model = "fake-model",
                protocolType = type,
                httpClient = recordingClient(urls),
            )

            val failure = runCatching { protocol.listModels() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(UnsupportedProviderOperationException::class.java)
            assertThat(urls).isEmpty()
        }
    }

    @Test
    fun `Vertex sync and stream make OAuth then official v1 wire requests`() = runTest {
        val fixture = validVertexCredentialJson()
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { captured ->
            urls += captured.url.toString()
            when {
                captured.url.host == "oauth2.googleapis.com" -> respond(
                    content = """{"access_token":"fake-token","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                captured.url.parameters["alt"] == "sse" -> respond(
                    content = "data: [DONE]\n\n",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
                else -> respond(
                    content = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"pong"}]}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        })
        val protocol = VertexAIProtocol(
            baseUrl = "https://us-central1-aiplatform.googleapis.com",
            serviceAccountJson = fixture,
            projectId = "",
            location = VERTEX_DEFAULT_LOCATION,
            model = "gemini-2.5-pro",
            httpClient = client,
        )

        protocol.sendPromptSync(request.copy(model = "gemini-2.5-pro"))
        protocol.sendPrompt(request.copy(model = "gemini-2.5-pro", stream = true)).toList()

        assertThat(urls).containsExactly(
            "https://oauth2.googleapis.com/token",
            "https://us-central1-aiplatform.googleapis.com/v1/projects/project-safe/locations/us-central1/publishers/google/models/gemini-2.5-pro:generateContent",
            "https://us-central1-aiplatform.googleapis.com/v1/projects/project-safe/locations/us-central1/publishers/google/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
        ).inOrder()
    }

    @Test
    fun `Vertex attacker origin and dot model fail before OAuth or HTTP`() = runTest {
        val fixture = validVertexCredentialJson()
        listOf(
            "https://attacker.example.invalid" to "gemini-2.5-pro",
            "https://us-central1-aiplatform.googleapis.com" to "..",
        ).forEach { (configuredBaseUrl, model) ->
            var requestCount = 0
            var bearerObserved = false
            val client = HttpClient(MockEngine { captured ->
                requestCount++
                bearerObserved = bearerObserved || captured.headers[HttpHeaders.Authorization]
                    ?.startsWith("Bearer ") == true
                respond(
                    content = """{"access_token":"fake-token","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            })
            val protocol = VertexAIProtocol(
                baseUrl = configuredBaseUrl,
                serviceAccountJson = fixture,
                projectId = "",
                location = VERTEX_DEFAULT_LOCATION,
                model = model,
                httpClient = client,
            )

            val error = runCatching {
                protocol.sendPromptSync(request.copy(model = model))
            }.exceptionOrNull()

            assertThat(error).isNotNull()
            assertThat(requestCount).isEqualTo(0)
            assertThat(bearerObserved).isFalse()
        }
    }

    private fun recordingClient(urls: MutableList<String>): HttpClient =
        HttpClient(MockEngine { captured ->
            urls += captured.url.toString()
            respond(
                content = "{}",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

    private fun validVertexCredentialJson(): String {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----"
        encoded.fill(0)
        return """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}"""
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
