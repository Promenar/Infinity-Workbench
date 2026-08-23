package com.promenar.nexara.data.remote

import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.middleware.LlmMiddleware
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.ProviderEndpointOperation
import com.promenar.nexara.data.remote.protocol.ProviderEndpointResolver
import com.promenar.nexara.data.remote.protocol.ProviderEndpointTarget
import com.promenar.nexara.data.remote.protocol.UnsupportedProviderProtocolException
import com.promenar.nexara.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.nexara.data.remote.provider.VertexCredentialParser
import com.promenar.nexara.data.remote.provider.VertexCredentialException
import com.promenar.nexara.data.model.ModelInfo
import java.net.URI

data class ResolvedProviderModel(
    val modelId: String,
    val remoteModelId: String,
    val providerId: String,
    val providerName: String,
    val config: UnifiedProviderConfig,
)

enum class ProviderResolutionError {
    MODEL_NOT_FOUND,
    MODEL_DISABLED,
    PROVIDER_ID_MISSING,
    PROVIDER_NOT_FOUND,
    PROVIDER_DISABLED,
    API_KEY_MISSING,
    BASE_URL_INVALID,
    MODEL_PROVIDER_MISMATCH,
    PROTOCOL_MISMATCH,
    PROTOCOL_UNSUPPORTED,
    LOCAL_INFERENCE_UNAVAILABLE,
    VERTEX_CREDENTIAL_INVALID,
}

sealed interface ProviderResolution {
    data class Success(val value: ResolvedProviderModel) : ProviderResolution
    data class Failure(
        val reason: ProviderResolutionError,
        val modelId: String,
        val providerId: String? = null,
    ) : ProviderResolution {
        override fun toString(): String = "ProviderResolution.Failure(reason=$reason)"
    }
}

interface ProviderRequestRouter {
    fun resolve(modelId: String): ProviderResolution
    fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient
}

fun stableModelId(providerId: String, remoteModelId: String): String =
    "$providerId::$remoteModelId"

class DefaultProviderRequestRouter(
    private val modelResolver: (String) -> ModelInfo?,
    private val providerResolver: (String) -> ProviderListItem?,
    private val configResolver: (String) -> ProviderConfig?,
    private val localInferenceAvailable: () -> Boolean = { BuildConfig.LOCAL_INFERENCE_AVAILABLE },
    private val clientFactory: (UnifiedProviderConfig) -> UnifiedLlmClient = { config ->
        UnifiedLlmClient(providerConfigResolver = { config })
    },
) : ProviderRequestRouter {

    constructor(providerManager: ProviderManager, middlewares: List<LlmMiddleware> = emptyList()) : this(
        modelResolver = { id -> providerManager.providerModels.value.firstOrNull { it.id == id } },
        providerResolver = { id -> providerManager.providers.value.firstOrNull { it.id == id } },
        configResolver = providerManager::getProviderConfig,
        clientFactory = { config -> UnifiedLlmClient({ config }, middlewares) },
    )

    override fun resolve(modelId: String): ProviderResolution {
        val model = modelResolver(modelId)
            ?: return failure(ProviderResolutionError.MODEL_NOT_FOUND, modelId)
        val providerId = model.providerId
            ?: return failure(ProviderResolutionError.PROVIDER_ID_MISSING, modelId)
        if (!model.enabled) {
            return failure(ProviderResolutionError.MODEL_DISABLED, modelId, providerId)
        }
        val expectedId = stableModelId(providerId, model.remoteModelId)
        if (model.id != expectedId || modelId != expectedId) {
            return failure(ProviderResolutionError.MODEL_PROVIDER_MISMATCH, modelId, providerId)
        }
        val provider = providerResolver(providerId)
            ?: return failure(ProviderResolutionError.PROVIDER_NOT_FOUND, modelId, providerId)
        if (!provider.enabled) {
            return failure(ProviderResolutionError.PROVIDER_DISABLED, modelId, providerId)
        }
        val raw = configResolver(providerId)
            ?: return failure(ProviderResolutionError.PROVIDER_NOT_FOUND, modelId, providerId)
        if (raw.protocolType::class != provider.protocolType::class) {
            return failure(ProviderResolutionError.PROTOCOL_MISMATCH, modelId, providerId)
        }
        if (raw.protocolType == ProtocolType.Cohere_Chat || raw.protocolType == ProtocolType.Yi_ZeroOne) {
            return failure(ProviderResolutionError.PROTOCOL_UNSUPPORTED, modelId, providerId)
        }
        if (raw.protocolType is ProtocolType.Local && !localInferenceAvailable()) {
            return failure(ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE, modelId, providerId)
        }
        if (raw.protocolType !is ProtocolType.Local && !isSecureCloudEndpoint(raw.baseUrl)) {
            return failure(ProviderResolutionError.BASE_URL_INVALID, modelId, providerId)
        }
        val credentialMissing = when (raw.protocolType) {
            is ProtocolType.Local -> false
            is ProtocolType.Google_VertexAI -> raw.vertexServiceAccountJson.isBlank()
            else -> raw.apiKey.isBlank()
        }
        if (credentialMissing) {
            return failure(ProviderResolutionError.API_KEY_MISSING, modelId, providerId)
        }
        val vertexProjectId = if (raw.protocolType is ProtocolType.Google_VertexAI) {
            try {
                VertexCredentialParser.parse(raw.vertexServiceAccountJson).projectId
            } catch (_: VertexCredentialException) {
                return failure(
                    ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
                    modelId,
                    providerId,
                )
            }
        } else {
            ""
        }
        try {
            when (raw.protocolType) {
                ProtocolType.Local -> Unit
                ProtocolType.Google_VertexAI -> ProviderEndpointResolver.resolve(
                    protocol = raw.protocolType,
                    configuredBaseUrl = raw.baseUrl,
                    target = ProviderEndpointTarget.VertexInference(
                        projectId = vertexProjectId,
                        location = VERTEX_DEFAULT_LOCATION,
                        model = model.remoteModelId,
                        streaming = false,
                    ),
                )
                else -> ProviderEndpointResolver.resolve(
                    protocol = raw.protocolType,
                    configuredBaseUrl = raw.baseUrl,
                    operation = ProviderEndpointOperation.INFERENCE,
                )
            }
        } catch (_: UnsupportedProviderProtocolException) {
            return failure(ProviderResolutionError.PROTOCOL_UNSUPPORTED, modelId, providerId)
        } catch (_: IllegalArgumentException) {
            return failure(ProviderResolutionError.BASE_URL_INVALID, modelId, providerId)
        }
        val config = UnifiedProviderConfig(
            protocolType = raw.protocolType,
            baseUrl = raw.baseUrl,
            apiKey = raw.apiKey,
            defaultModel = model.remoteModelId,
            serviceAccountJson = raw.vertexServiceAccountJson,
            projectId = vertexProjectId,
            location = VERTEX_DEFAULT_LOCATION,
        )
        return ProviderResolution.Success(
            ResolvedProviderModel(
                modelId = model.id,
                remoteModelId = model.remoteModelId,
                providerId = providerId,
                providerName = provider.name,
                config = config,
            )
        )
    }

    override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
        clientFactory(resolved.config)

    private fun failure(
        reason: ProviderResolutionError,
        modelId: String,
        providerId: String? = null,
    ) = ProviderResolution.Failure(reason, modelId, providerId)

    private fun isSecureCloudEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

}
