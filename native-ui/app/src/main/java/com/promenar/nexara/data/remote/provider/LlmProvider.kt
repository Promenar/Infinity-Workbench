package com.promenar.nexara.data.remote.provider

import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.LocalProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
        private var serviceAccountJson: String = ""
        private var projectId: String = ""
        private var location: String = VERTEX_DEFAULT_LOCATION

        fun protocolId(id: ProtocolType) = apply { this.protocolType = id }
        fun protocolType(type: ProtocolType) = apply { this.protocolType = type }
        fun baseUrl(url: String) = apply { this.baseUrl = url }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun model(model: String) = apply { this.model = model }
        fun serviceAccountJson(json: String) = apply { this.serviceAccountJson = json }
        fun projectId(id: String) = apply { this.projectId = id }
        fun location(loc: String) = apply { this.location = loc }

        fun build(): LlmProvider {
            val protocol = createProtocol(
                protocolType, baseUrl, apiKey, model,
                serviceAccountJson, projectId, location
            )
            return LlmProvider(protocol)
        }
    }

    companion object {
        fun builder(): Builder = Builder()

        fun resolving(
            protocolType: ProtocolType,
            resolver: () -> LlmProtocol,
        ): LlmProvider = LlmProvider(ResolvingLlmProtocol(protocolType, resolver))

        private fun createProtocol(
            type: ProtocolType,
            baseUrl: String,
            apiKey: String,
            model: String,
            serviceAccountJson: String = "",
            projectId: String = "",
            location: String = VERTEX_DEFAULT_LOCATION
        ): LlmProtocol {
            if (type == ProtocolType.Local) {
                throw IllegalStateException("Use LlmProvider.local(engine) factory for local models")
            }
            return ProtocolFactory.create(
                type = type,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                serviceAccountJson = serviceAccountJson,
                projectId = projectId,
                location = location,
            )
        }

        fun local(engine: LocalInferenceEngine, modelName: String = ""): LlmProvider {
            return LlmProvider(LocalProtocol(engine, modelName))
        }
    }
}

private class ResolvingLlmProtocol(
    override val protocolType: ProtocolType,
    private val resolver: () -> LlmProtocol,
) : LlmProtocol {
    @Volatile
    private var active: LlmProtocol? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
        val delegate = resolver()
        active = delegate
        try {
            emitAll(delegate.sendPrompt(request))
        } finally {
            if (active === delegate) active = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val delegate = resolver()
        active = delegate
        return try {
            delegate.sendPromptSync(request)
        } finally {
            if (active === delegate) active = null
        }
    }

    override suspend fun listModels(): List<String> {
        val delegate = resolver()
        active = delegate
        return try {
            delegate.listModels()
        } finally {
            if (active === delegate) active = null
        }
    }

    override fun cancel() {
        active?.cancel()
    }
}
