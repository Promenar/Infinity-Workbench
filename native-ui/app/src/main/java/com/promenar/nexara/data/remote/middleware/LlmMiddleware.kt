package com.promenar.nexara.data.remote.middleware

import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.StreamChunk
import kotlinx.coroutines.Job

enum class MiddlewareEnforce { PRE, NORMAL, POST }

data class StreamTextParams(
    val messages: List<ProtocolMessage>,
    val model: String,
    val system: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val tools: Map<String, ProtocolTool>? = null,
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
