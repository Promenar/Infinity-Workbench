package com.promenar.infinity.data.remote.middleware

import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.StreamChunk
import kotlinx.coroutines.Job

enum class MiddlewareEnforce { PRE, NORMAL, POST }

data class StreamTextParams(
    val messages: List<ProtocolMessage>,
    val model: String,
    val system: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topK: Int? = null,
    val repetitionPenalty: Double? = null,
    val reasoningEffort: com.promenar.infinity.data.model.params.ReasoningEffort? = null,
    val streamTimeout: Long? = null,
    val enableGeminiSearch: Boolean? = null,
    val tools: Map<String, ProtocolTool>? = null,
    val toolChoice: com.promenar.infinity.data.remote.protocol.ProtocolToolChoice =
        com.promenar.infinity.data.remote.protocol.ProtocolToolChoice.AUTO,
    val maxToolCalls: Int = 10,
    val abortSignal: Job? = null,
    val enableWebSearch: Boolean = false,
    val enableKnowledgeSearch: Boolean = false,
    val enableMemorySearch: Boolean = false,
    val extra: MutableMap<String, Any?> = mutableMapOf()
)

interface LlmMiddleware {
    val name: String
    val enforce: MiddlewareEnforce get() = MiddlewareEnforce.NORMAL

    suspend fun onRequestStart(params: StreamTextParams) {}
    suspend fun transformParams(params: StreamTextParams): StreamTextParams { return params }
    suspend fun transformStreamChunk(
        rawChunk: StreamChunk,
        emitter: suspend (StreamChunk) -> Unit
    ) { emitter(rawChunk) }
    suspend fun onRequestEnd(params: StreamTextParams) {}
}
