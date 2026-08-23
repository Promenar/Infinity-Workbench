package com.promenar.nexara.data.remote.protocol

import com.promenar.nexara.data.local.inference.LocalInferenceEngine

/**
 * Factory for creating LlmProtocol instances based on ProtocolType.
 */
object ProtocolFactory {
    fun create(
        type: ProtocolType,
        baseUrl: String = "",
        apiKey: String = "",
        model: String = "",
        serviceAccountJson: String = "",
        projectId: String = "",
        location: String = VERTEX_DEFAULT_LOCATION,
        localEngine: LocalInferenceEngine? = null
    ): LlmProtocol {
        return when (type) {
            ProtocolType.OpenAI_ChatCompletions -> OpenAIProtocol(baseUrl, apiKey, model)
            ProtocolType.OpenAI_Responses -> OpenAIResponsesProtocol(baseUrl, apiKey, model)
            
            ProtocolType.Anthropic_Messages -> AnthropicProtocol(baseUrl, apiKey, model)
            
            ProtocolType.Google_VertexAI -> VertexAIProtocol(
                baseUrl = baseUrl,
                serviceAccountJson = serviceAccountJson,
                projectId = projectId,
                location = location,
                model = model
            )
            
            ProtocolType.Cohere_Chat,
            ProtocolType.Yi_ZeroOne,
            -> throw UnsupportedProviderProtocolException(type)

            ProtocolType.Mistral_Chat,
            ProtocolType.Generic_OpenAI_Compat,
            ProtocolType.Moonshot_Kimi,
            ProtocolType.Qwen_DashScope,
            ProtocolType.Zhipu_GLM,
            ProtocolType.Doubao_ByteDance,
            ProtocolType.Baichuan,
            ProtocolType.DeepSeek,
            -> GenericOpenAICompatProtocol(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                protocolType = type,
            )
            
            ProtocolType.Local -> {
                if (localEngine == null) {
                    throw IllegalArgumentException("LocalInferenceEngine is required for Local protocol")
                }
                LocalProtocol(localEngine, model)
            }
        }
    }
}
