package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.OpenAIProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolId
import com.promenar.nexara.data.remote.protocol.StreamChunk
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

        fun protocolId(id: ProtocolId) = apply { this.protocolId = id }
        fun baseUrl(url: String) = apply { this.baseUrl = url }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun model(model: String) = apply { this.model = model }

        fun build(): LlmProvider {
            val protocol = createProtocol(protocolId, baseUrl, apiKey, model)
            return LlmProvider(protocol)
        }
    }

    companion object {
        fun builder(): Builder = Builder()

        private fun createProtocol(
            id: ProtocolId,
            baseUrl: String,
            apiKey: String,
            model: String
        ): LlmProtocol = when (id) {
            ProtocolId.OPENAI -> OpenAIProtocol(baseUrl, apiKey, model)
            ProtocolId.ANTHROPIC -> throw NotImplementedError(
                "AnthropicProtocol is not yet implemented."
            )
            ProtocolId.VERTEX_AI -> throw NotImplementedError(
                "VertexAIProtocol is not yet implemented."
            )
        }
    }
}
