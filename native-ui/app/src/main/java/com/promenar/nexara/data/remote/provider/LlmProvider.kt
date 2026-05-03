package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.remote.protocol.AnthropicProtocol
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.OpenAIProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.protocol.VertexAIProtocol
import kotlinx.coroutines.flow.Flow

class LlmProvider(private val protocol: LlmProtocol) {

    val protocolId: ProtocolId get() = protocol.id

    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> =
        protocol.sendPrompt(request)

    suspend fun sendPromptSync(request: PromptRequest): PromptResponse =
        protocol.sendPromptSync(request)

    fun cancel() = protocol.cancel()

    class Builder {
        private var protocolId: ProtocolId = ProtocolId.OPENAI
        private var baseUrl: String = ""
        private var apiKey: String = ""
        private var model: String = ""
        private var serviceAccountKeyPath: String = ""
        private var projectId: String = ""
        private var location: String = "us-central1"

        fun protocolId(id: ProtocolId) = apply { this.protocolId = id }
        fun baseUrl(url: String) = apply { this.baseUrl = url }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun model(model: String) = apply { this.model = model }
        fun serviceAccountKeyPath(path: String) = apply { this.serviceAccountKeyPath = path }
        fun projectId(id: String) = apply { this.projectId = id }
        fun location(loc: String) = apply { this.location = loc }

        fun build(): LlmProvider {
            val protocol = createProtocol(
                protocolId, baseUrl, apiKey, model,
                serviceAccountKeyPath, projectId, location
            )
            return LlmProvider(protocol)
        }
    }

    companion object {
        fun builder(): Builder = Builder()

        private fun createProtocol(
            id: ProtocolId,
            baseUrl: String,
            apiKey: String,
            model: String,
            serviceAccountKeyPath: String = "",
            projectId: String = "",
            location: String = "us-central1"
        ): LlmProtocol = when (id) {
            ProtocolId.OPENAI -> OpenAIProtocol(baseUrl, apiKey, model)
            ProtocolId.ANTHROPIC -> AnthropicProtocol(baseUrl, apiKey, model)
            ProtocolId.VERTEX_AI -> VertexAIProtocol(
                serviceAccountKeyPath = serviceAccountKeyPath,
                projectId = projectId,
                location = location,
                model = model
            )
        }
    }
}
