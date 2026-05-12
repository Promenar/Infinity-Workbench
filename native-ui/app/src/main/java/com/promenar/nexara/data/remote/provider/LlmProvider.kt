package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.remote.protocol.AnthropicProtocol
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.LocalProtocol
import com.promenar.nexara.data.remote.protocol.OpenAIProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.protocol.VertexAIProtocol
import kotlinx.coroutines.flow.Flow

class LlmProvider(internal val protocol: LlmProtocol) {

    /** @deprecated 使用 protocolType 代替，保留此属性用于向后兼容 */
    val protocolId: ProtocolType get() = protocol.protocolType
    val protocolType: ProtocolType get() = protocol.protocolType

    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> =
        protocol.sendPrompt(request)

    suspend fun sendPromptSync(request: PromptRequest): PromptResponse =
        protocol.sendPromptSync(request)

    suspend fun listModels(): List<String> = protocol.listModels()

    fun cancel() = protocol.cancel()

    class Builder {
        private var protocolType: ProtocolType = ProtocolType.OpenAI_ChatCompletions
        private var baseUrl: String = ""
        private var apiKey: String = ""
        private var model: String = ""
        private var serviceAccountKeyPath: String = ""
        private var projectId: String = ""
        private var location: String = "us-central1"

        fun protocolId(id: ProtocolType) = apply { this.protocolType = id }
        fun protocolType(type: ProtocolType) = apply { this.protocolType = type }
        fun baseUrl(url: String) = apply { this.baseUrl = url }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun model(model: String) = apply { this.model = model }
        fun serviceAccountKeyPath(path: String) = apply { this.serviceAccountKeyPath = path }
        fun projectId(id: String) = apply { this.projectId = id }
        fun location(loc: String) = apply { this.location = loc }

        fun build(): LlmProvider {
            val protocol = createProtocol(
                protocolType, baseUrl, apiKey, model,
                serviceAccountKeyPath, projectId, location
            )
            return LlmProvider(protocol)
        }
    }

    companion object {
        fun builder(): Builder = Builder()

        private fun createProtocol(
            type: ProtocolType,
            baseUrl: String,
            apiKey: String,
            model: String,
            serviceAccountKeyPath: String = "",
            projectId: String = "",
            location: String = "us-central1"
        ): LlmProtocol = when (type) {
            is ProtocolType.OpenAI_ChatCompletions -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.OpenAI_Responses -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.Anthropic_Messages -> AnthropicProtocol(baseUrl, apiKey, model)
            is ProtocolType.Google_VertexAI -> VertexAIProtocol(
                serviceAccountKeyPath = serviceAccountKeyPath,
                projectId = projectId,
                location = location,
                model = model
            )
            is ProtocolType.Cohere_Chat -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.Mistral_Chat -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.DeepSeek -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.Generic_OpenAI_Compat -> OpenAIProtocol(baseUrl, apiKey, model)
            is ProtocolType.Local -> throw IllegalStateException(
                "Use LlmProvider.local(engine) factory for local models"
            )
        }

        fun local(engine: LocalInferenceEngine, modelName: String = ""): LlmProvider {
            return LlmProvider(LocalProtocol(engine, modelName))
        }
    }
}
