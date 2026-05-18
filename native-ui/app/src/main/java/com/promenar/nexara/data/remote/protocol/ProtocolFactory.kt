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
        serviceAccountKeyPath: String = "",
        projectId: String = "",
        location: String = "us-central1",
        localEngine: LocalInferenceEngine? = null
    ): LlmProtocol {
        return when (type) {
            ProtocolType.OpenAI_ChatCompletions,
            ProtocolType.OpenAI_Responses -> OpenAIProtocol(baseUrl, apiKey, model)
            
            ProtocolType.Anthropic_Messages -> AnthropicProtocol(baseUrl, apiKey, model)
            
            ProtocolType.Google_VertexAI -> VertexAIProtocol(
                serviceAccountKeyPath = serviceAccountKeyPath,
                projectId = projectId,
                location = location,
                model = model
            )
            
            ProtocolType.Cohere_Chat,
            ProtocolType.Mistral_Chat,
            ProtocolType.Generic_OpenAI_Compat,
            ProtocolType.Moonshot_Kimi,
            ProtocolType.Qwen_DashScope,
            ProtocolType.Zhipu_GLM,
            ProtocolType.Doubao_ByteDance,
            ProtocolType.Yi_ZeroOne,
            ProtocolType.Baichuan -> GenericOpenAICompatProtocol(baseUrl, apiKey, model)
            
            ProtocolType.DeepSeek -> OpenAIProtocol(baseUrl, apiKey, model)
            
            ProtocolType.Local -> {
                if (localEngine == null) {
                    throw IllegalArgumentException("LocalInferenceEngine is required for Local protocol")
                }
                LocalProtocol(localEngine, model)
            }
        }
    }
}
