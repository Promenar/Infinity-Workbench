package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.protocol.ProviderEndpointOperation
import com.promenar.nexara.data.remote.protocol.ProviderEndpointResolver
import com.promenar.nexara.data.remote.protocol.ProviderEndpointTarget
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.UnsupportedProviderOperationException
import com.promenar.nexara.data.remote.protocol.UnsupportedProviderProtocolException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderConnectionProbeFailure {
    CREDENTIALS_MISSING,
    CREDENTIALS_INVALID,
    AUTHENTICATION_REJECTED,
    NETWORK_UNAVAILABLE,
    RESPONSE_INVALID,
}

sealed interface ProviderConnectionProbeResult {
    data object Success : ProviderConnectionProbeResult
    data object Unsupported : ProviderConnectionProbeResult

    data class Failure(
        val reason: ProviderConnectionProbeFailure,
    ) : ProviderConnectionProbeResult {
        override fun toString(): String = "ProviderConnectionProbeResult.Failure(reason=$reason)"
    }
}

/**
 * 唯一 Provider 连接探测入口。只访问权威无推理成本端点；不发送 prompt，也不调用 generation。
 */
class ProviderConnectionProbe(
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(config: UnifiedProviderConfig): ProviderConnectionProbeResult {
        return try {
            when (config.protocolType) {
                ProtocolType.Local -> ProviderConnectionProbeResult.Success
                ProtocolType.Google_VertexAI -> probeVertex(config)
                else -> probeApiKeyProvider(config)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedProviderOperationException) {
            ProviderConnectionProbeResult.Unsupported
        } catch (_: UnsupportedProviderProtocolException) {
            ProviderConnectionProbeResult.Unsupported
        } catch (_: VertexCredentialException) {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.CREDENTIALS_INVALID)
        } catch (_: Exception) {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE)
        }
    }

    private suspend fun probeApiKeyProvider(
        config: UnifiedProviderConfig,
    ): ProviderConnectionProbeResult {
        if (config.apiKey.isBlank()) {
            return ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.CREDENTIALS_MISSING,
            )
        }
        val endpoint = ProviderEndpointResolver.resolve(
            config.protocolType,
            config.baseUrl,
            ProviderEndpointOperation.CONNECTION_PROBE,
        )
        val response = httpClient.get(endpoint) {
            when (config.protocolType) {
                ProtocolType.Anthropic_Messages -> {
                    header("x-api-key", config.apiKey)
                    header("anthropic-version", "2023-06-01")
                }
                else -> header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
        }
        return if (response.status.isSuccess()) {
            ProviderConnectionProbeResult.Success
        } else {
            ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
            )
        }
    }

    private suspend fun probeVertex(
        config: UnifiedProviderConfig,
    ): ProviderConnectionProbeResult {
        if (config.serviceAccountJson.isBlank()) {
            return ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.CREDENTIALS_MISSING,
            )
        }
        val credential = VertexCredentialParser.parse(
            serviceAccountJson = config.serviceAccountJson,
            explicitProjectId = config.projectId,
        )
        val assertion = VertexCredentialParser.createJwtAssertion(credential)
        val endpoint = ProviderEndpointResolver.resolve(
            ProtocolType.Google_VertexAI,
            config.baseUrl,
            ProviderEndpointTarget.VertexOAuthToken,
        )
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$assertion",
            )
        }
        if (!response.status.isSuccess()) {
            return ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
            )
        }
        val hasAccessToken = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject["access_token"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.isNotBlank() == true
        } catch (_: Exception) {
            false
        }
        return if (hasAccessToken) {
            ProviderConnectionProbeResult.Success
        } else {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.RESPONSE_INVALID)
        }
    }
}
