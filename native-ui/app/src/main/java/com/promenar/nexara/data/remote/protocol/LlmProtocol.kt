package com.promenar.nexara.data.remote.protocol

import kotlinx.coroutines.flow.Flow
import com.promenar.nexara.R
import kotlinx.serialization.Serializable

@Serializable
data class ImageInput(
    val url: String? = null,
    val base64: String? = null,
    val mimeType: String = "image/png"
)

@Serializable
data class AudioInput(
    val base64: String,
    val mimeType: String = "audio/wav"
)

@Serializable
data class DocumentInput(
    val url: String? = null,
    val base64: String? = null,
    val name: String? = null,
    val mimeType: String = "application/pdf"
)

@Serializable
data class ProtocolMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ProtocolToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<ProtocolFileAttachment>? = null,
    val imageUrls: List<ImageInput>? = null,
    val audioData: List<AudioInput>? = null,
    val documentData: List<DocumentInput>? = null
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
    val topK: Int? = null,
    val repetitionPenalty: Double? = null,
    val tools: List<ProtocolTool>? = null,
    val stream: Boolean = true,
    val reasoning: Boolean? = null,
    val webSearch: Boolean? = null,
    val streamTimeout: Long? = null,
    val images: List<ImageInput>? = null,
    val audio: List<AudioInput>? = null,
    val documents: List<DocumentInput>? = null
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

sealed class ProtocolType(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultPath: String,
    val iconRes: Int? = null
) {
    data object OpenAI_ChatCompletions : ProtocolType(
        "OpenAI Chat Completions", "https://api.openai.com", "/v1/chat/completions", R.drawable.ic_provider_openai
    )
    data object OpenAI_Responses : ProtocolType(
        "OpenAI Responses", "https://api.openai.com", "/v1/responses", R.drawable.ic_provider_openai
    )
    data object Anthropic_Messages : ProtocolType(
        "Anthropic Messages", "https://api.anthropic.com", "/v1/messages", R.drawable.ic_provider_anthropic
    )
    data object Google_VertexAI : ProtocolType(
        "Google Vertex AI", "https://generativelanguage.googleapis.com", "/v1beta/models", R.drawable.ic_provider_gemini
    )
    data object Cohere_Chat : ProtocolType(
        "Cohere Chat", "https://api.cohere.ai", "/v2/chat", R.drawable.ic_provider_cohere
    )
    data object Mistral_Chat : ProtocolType(
        "Mistral Chat", "https://api.mistral.ai", "/v1/chat/completions", R.drawable.ic_provider_mistral
    )
    data object DeepSeek : ProtocolType(
        "DeepSeek", "https://api.deepseek.com", "/v1/chat/completions", R.drawable.ic_provider_deepseek
    )
    data object Generic_OpenAI_Compat : ProtocolType(
        "OpenAI 兼容 (通用)", "", "/v1/chat/completions", R.drawable.ic_provider_custom
    )
    data object Local : ProtocolType("本地推理", "", "", R.drawable.ic_provider_local)

    companion object {
        // ── 向后兼容别名 (旧 ProtocolId 枚举名) ──────────────────
        @Deprecated("Use OpenAI_ChatCompletions", ReplaceWith("OpenAI_ChatCompletions"))
        val OPENAI: ProtocolType get() = OpenAI_ChatCompletions
        @Deprecated("Use Anthropic_Messages", ReplaceWith("Anthropic_Messages"))
        val ANTHROPIC: ProtocolType get() = Anthropic_Messages
        @Deprecated("Use Google_VertexAI", ReplaceWith("Google_VertexAI"))
        val VERTEX_AI: ProtocolType get() = Google_VertexAI
        @Deprecated("Use Local", ReplaceWith("Local"))
        val LOCAL: ProtocolType get() = Local

        /** 从旧 ProtocolId 枚举名迁移到新 ProtocolType */
        fun fromLegacyName(name: String): ProtocolType = when (name.uppercase()) {
            "OPENAI" -> OpenAI_ChatCompletions
            "ANTHROPIC" -> Anthropic_Messages
            "VERTEX_AI" -> Google_VertexAI
            "LOCAL" -> Local
            else -> try {
                entries.first { it::class.simpleName == name }
            } catch (_: Exception) {
                Generic_OpenAI_Compat
            }
        }

        val entries: List<ProtocolType> = listOf(
            OpenAI_ChatCompletions, OpenAI_Responses, Anthropic_Messages,
            Google_VertexAI, Cohere_Chat, Mistral_Chat, DeepSeek,
            Generic_OpenAI_Compat, Local
        )
    }
}

/**
 * 向后兼容别名 — 旧代码中的 ProtocolId 引用使用此别名过渡。
 * 新代码请直接使用 ProtocolType。
 */
typealias ProtocolId = ProtocolType

interface LlmProtocol {
    val protocolType: ProtocolType

    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk>

    suspend fun sendPromptSync(request: PromptRequest): PromptResponse
    suspend fun listModels(): List<String> = emptyList()
    fun cancel()
}
