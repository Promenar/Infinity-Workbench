package com.promenar.nexara.data.remote

import com.promenar.nexara.BuildConfig
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.middleware.LlmMiddleware
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.model.ModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

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
            parseVertexProjectId(raw.vertexServiceAccountJson)
                ?: return failure(
                    ProviderResolutionError.VERTEX_CREDENTIAL_INVALID,
                    modelId,
                    providerId,
                )
        } else {
            ""
        }
        val config = UnifiedProviderConfig(
            protocolType = raw.protocolType,
            baseUrl = raw.baseUrl,
            apiKey = raw.apiKey,
            defaultModel = model.remoteModelId,
            serviceAccountJson = raw.vertexServiceAccountJson,
            projectId = vertexProjectId,
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

    private fun parseVertexProjectId(credentialsJson: String): String? = runCatching {
        val credentials = Json.parseToJsonElement(credentialsJson).jsonObject
        fun requiredString(name: String): String = credentials[name]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            .orEmpty()
        val projectId = requiredString("project_id")
        val clientEmail = requiredString("client_email")
        val privateKey = requiredString("private_key")
        projectId.takeIf {
            it.isNotEmpty() &&
                clientEmail.isNotEmpty() &&
                isValidPkcs8RsaPrivateKey(privateKey)
        }
    }.getOrNull()

    private fun isValidPkcs8RsaPrivateKey(pem: String): Boolean = runCatching {
        val payload = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot(Char::isWhitespace)
        require(payload.isNotEmpty())
        val encoded = Base64.getDecoder().decode(payload)
        try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        } finally {
            encoded.fill(0)
        }
        true
    }.getOrDefault(false)
}
