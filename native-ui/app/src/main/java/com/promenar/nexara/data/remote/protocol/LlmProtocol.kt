package com.promenar.nexara.data.remote.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class ProtocolMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ProtocolToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<ProtocolFileAttachment>? = null
)

@Serializable
data class ProtocolToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ProtocolFileAttachment(
    val uri: String,
    val mimeType: String,
    val name: String? = null
)

@Serializable
data class ProtocolTool(
    val type: String = "function",
    val function: ProtocolToolFunction
)

@Serializable
data class ProtocolToolFunction(
    val name: String,
    val description: String,
    val parameters: String
)

@Serializable
data class ProtocolUsage(
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0
)

@Serializable
data class PromptRequest(
    val messages: List<ProtocolMessage>,
    val model: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val tools: List<ProtocolTool>? = null,
    val stream: Boolean = true,
    val reasoning: Boolean? = null,
    val webSearch: Boolean? = null
)

@Serializable
data class PromptResponse(
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<ProtocolToolCall>? = null,
    val usage: ProtocolUsage? = null,
    val citations: List<ProtocolCitation>? = null
)

@Serializable
data class ProtocolCitation(
    val title: String,
    val url: String,
    val source: String? = null
)

sealed class StreamChunk {
    data class TextDelta(
        val content: String,
        val reasoning: String? = null
    ) : StreamChunk()

    data class ToolCallDelta(
        val id: String,
        val name: String,
        val arguments: String,
        val index: Int
    ) : StreamChunk()

    data class Thinking(
        val content: String
    ) : StreamChunk()

    data class Usage(
        val usage: ProtocolUsage
    ) : StreamChunk()

    data class Citations(
        val citations: List<ProtocolCitation>
    ) : StreamChunk()

    data class Error(
        val message: String,
        val retryable: Boolean = false,
        val category: String? = null
    ) : StreamChunk()

    data object Done : StreamChunk()
}

enum class ProtocolId {
    OPENAI,
    ANTHROPIC,
    VERTEX_AI
}

interface LlmProtocol {
    val id: ProtocolId

    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk>

    suspend fun sendPromptSync(request: PromptRequest): PromptResponse

    fun cancel()
}
