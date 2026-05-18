package com.promenar.nexara.data.remote

import com.promenar.nexara.data.remote.lifecycle.ToolCallLifecycleHandler
import com.promenar.nexara.data.remote.middleware.LlmMiddleware
import com.promenar.nexara.data.remote.middleware.LlmMiddlewareChain
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

data class UnifiedProviderConfig(
    val protocolType: ProtocolType,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val serviceAccountKeyPath: String = "",
    val projectId: String = "",
    val location: String = "us-central1"
)

data class StreamConfig(
    val tools: Map<String, ProtocolTool> = emptyMap(),
    val maxToolCalls: Int = 10,
    val enableWebSearch: Boolean = false,
    val enableUrlContext: Boolean = false,
    val webSearchProviderId: String? = null,
    val knowledgeBaseIds: List<String> = emptyList(),
    val enableMemory: Boolean = false,
    val assistantId: String = "",
    val topicId: String = ""
)

class UnifiedLlmClient(
    private val providerConfig: UnifiedProviderConfig,
    private val middlewares: List<LlmMiddleware> = emptyList()
) {
    suspend fun sendStream(
        params: StreamTextParams,
        config: StreamConfig
    ): Flow<StreamChunk> = channelFlow {
        val protocol = ProtocolFactory.create(
            type = providerConfig.protocolType,
            baseUrl = providerConfig.baseUrl,
            apiKey = providerConfig.apiKey,
            model = providerConfig.defaultModel,
            serviceAccountKeyPath = providerConfig.serviceAccountKeyPath,
            projectId = providerConfig.projectId,
            location = providerConfig.location
        )

        val chain = LlmMiddlewareChain(middlewares)

        chain.onRequestStart(params)
        val finalParams = chain.transformParams(params)

        val allTools = (config.tools + (finalParams.tools ?: emptyMap())).values.toList()

        val request = PromptRequest(
            messages = finalParams.messages,
            model = finalParams.model,
            temperature = finalParams.temperature,
            topP = finalParams.topP,
            maxTokens = finalParams.maxOutputTokens,
            tools = allTools.ifEmpty { null },
            stream = true,
            webSearch = finalParams.enableWebSearch || config.enableWebSearch
        )

        val lifecycleHandler = ToolCallLifecycleHandler(
            onChunk = { trySend(it) },
            knownTools = config.tools
        )

        try {
            val rawFlow = protocol.sendPrompt(request)
            rawFlow.collect { rawChunk ->
                chain.transformStreamChunk(rawChunk) { transformed ->
                    when (transformed) {
                        is StreamChunk.ToolCallDelta -> {
                            lifecycleHandler.handleToolInputDelta(
                                transformed.id,
                                transformed.arguments
                            )
                            send(transformed)
                        }
                        else -> trySend(transformed)
                    }
                }
            }
        } catch (e: Exception) {
            NexaraLogger.logError("[UnifiedLlmClient] Stream error", e)
            trySend(StreamChunk.Error(
                message = e.message ?: "Unknown stream error",
                retryable = true
            ))
        }

        chain.onRequestEnd(finalParams)
        // 不使用 awaitClose {}：底层协议流结束后 channelFlow 自动关闭。
        // awaitClose {} 会导致 Flow 永不完成，使 ChatViewModel 的 collect {} 永远挂起，
        // isGenerating 卡在 true 无法恢复。
    }
}
