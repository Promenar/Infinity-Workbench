package com.promenar.nexara.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderConnectionProbeTest {
    @Test
    fun `Anthropic empty models response is a successful no inference probe`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedApiKey: String? = null
        var capturedVersion: String? = null
        val urls = mutableListOf<String>()
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine { request ->
                urls += request.url.toString()
                capturedMethod = request.method
                capturedApiKey = request.headers["x-api-key"]
                capturedVersion = request.headers["anthropic-version"]
                respond(
                    content = """{"data":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Anthropic_Messages,
                baseUrl = "https://api.anthropic.com",
                apiKey = "fake-key",
                defaultModel = "",
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(urls).containsExactly("https://api.anthropic.com/v1/models?limit=1")
        assertThat(capturedMethod).isEqualTo(HttpMethod.Get)
        assertThat(capturedApiKey).isEqualTo("fake-key")
        assertThat(capturedVersion).isEqualTo("2023-06-01")
    }

    @Test
    fun `Vertex connection probe performs OAuth exchange only and never generation`() = runTest {
        val fixture = validCredentialJson()
        val urls = mutableListOf<String>()
        var formBody = ""
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine { request ->
                urls += request.url.toString()
                formBody = request.body.toByteReadPacket().readText()
                respond(
                    content = """{"access_token":"fake-access-token","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson = fixture.json,
                projectId = "",
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(urls).containsExactly("https://oauth2.googleapis.com/token")
        assertThat(urls.joinToString()).doesNotContain("generateContent")
        assertThat(formBody).contains("grant_type=")
        assertThat(formBody).contains("assertion=")
        assertThat(formBody).doesNotContain(fixture.privateKeyPem)
    }

    @Test
    fun `unsupported no cost probe is not reported as failure or success and sends no HTTP`() = runTest {
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("不应请求")
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Qwen_DashScope,
                baseUrl = ProtocolType.Qwen_DashScope.defaultBaseUrl,
                apiKey = "fake-key",
                defaultModel = "",
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Unsupported)
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `probe failure never includes upstream body credentials or private key`() = runTest {
        val fixture = validCredentialJson()
        val upstreamMarker = "UPSTREAM-SECRET-MARKER"
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                respond(
                    content = upstreamMarker,
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson = fixture.json,
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isInstanceOf(ProviderConnectionProbeResult.Failure::class.java)
        assertThat(result.toString()).doesNotContain(upstreamMarker)
        assertThat(result.toString()).doesNotContain(fixture.privateKeyPem)
        assertThat(result.toString()).doesNotContain(fixture.json)
    }

    private fun validCredentialJson(): CredentialFixture {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----"
        encoded.fill(0)
        val json = """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}"""
        return CredentialFixture(json, pem)
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

    private data class CredentialFixture(val json: String, val privateKeyPem: String)
}
