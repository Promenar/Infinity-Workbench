package com.promenar.infinity.data.remote

import com.promenar.infinity.data.remote.protocol.ProtocolType

class ModelRequestRouteException(val reason: ProviderResolutionError, message: String) : IllegalStateException(message)

fun ProviderRequestRouter.requireModelRoute(
    modelId: String,
    errorMessage: (ProviderResolutionError) -> String = ::modelRouteToolError,
): ResolvedProviderModel = when (val result = resolve(modelId)) {
    is ProviderResolution.Success -> result.value
    is ProviderResolution.Failure -> throw ModelRequestRouteException(result.reason, errorMessage(result.reason))
}

/** Images、Embedding 和 Rerank 客户端仅实现 OpenAI 兼容的 HTTP 接口。 */
fun ResolvedProviderModel.requireOpenAiModelRoute(
    errorMessage: (ProviderResolutionError) -> String = ::modelRouteToolError,
): ResolvedProviderModel {
    when (config.protocolType) {
        ProtocolType.Anthropic_Messages, ProtocolType.Google_Gemini, ProtocolType.Google_VertexAI,
        ProtocolType.Cohere_Chat, ProtocolType.Yi_ZeroOne, ProtocolType.Local -> {
            val reason = ProviderResolutionError.PROTOCOL_UNSUPPORTED
            throw ModelRequestRouteException(reason, errorMessage(reason))
        }
        else -> Unit
    }
    return this
}

private fun modelRouteToolError(reason: ProviderResolutionError): String {
    val explanation = when (reason) {
        ProviderResolutionError.MODEL_NOT_FOUND -> "The selected model no longer exists"
        ProviderResolutionError.MODEL_DISABLED -> "The selected model is disabled"
        ProviderResolutionError.PROVIDER_ID_MISSING, ProviderResolutionError.MODEL_PROVIDER_MISMATCH -> "The model's provider association is invalid"
        ProviderResolutionError.PROVIDER_NOT_FOUND -> "The selected model's provider no longer exists"
        ProviderResolutionError.PROVIDER_DISABLED -> "The selected model's provider is disabled"
        ProviderResolutionError.API_KEY_MISSING -> "The selected provider has no credentials"
        ProviderResolutionError.BASE_URL_INVALID -> "The selected provider's base URL is invalid"
        ProviderResolutionError.PROTOCOL_MISMATCH -> "The selected provider's protocol configuration is inconsistent"
        ProviderResolutionError.PROTOCOL_UNSUPPORTED -> "The selected provider's protocol is unsupported for this request"
        ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE -> "Local inference is unavailable"
        ProviderResolutionError.VERTEX_CREDENTIAL_INVALID -> "The selected provider's Vertex credentials are invalid"
    }
    return "Model route failed ($reason): $explanation. Check Settings → Models and Providers."
}
