package com.promenar.nexara.data.remote

import com.promenar.nexara.data.remote.parser.ToolCall

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL;

    fun toApiString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): MessageRole =
            entries.first { it.name.equals(value, ignoreCase = true) }
    }
}

data class FileAttachment(
    val uri: String,
    val mimeType: String,
    val name: String? = null
)

data class FormatterMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<FileAttachment>? = null
)

data class LlmChatMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<FileAttachment>? = null
)

interface MessageFormatter {
    fun formatHistory(messages: List<FormatterMessage>, contextWindow: Int? = null): List<LlmChatMessage>
    fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean
    fun supportsReasoningInHistory(): Boolean
}

abstract class BaseMessageFormatter : MessageFormatter {

    abstract override fun formatHistory(
        messages: List<FormatterMessage>,
        contextWindow: Int?
    ): List<LlmChatMessage>

    override fun shouldStripHangingToolCalls(message: FormatterMessage): Boolean = false

    override fun supportsReasoningInHistory(): Boolean = false

    protected fun convertMessage(message: FormatterMessage): LlmChatMessage =
        LlmChatMessage(
            role = message.role,
            content = message.content,
            reasoning = message.reasoning,
            name = message.name,
            toolCallId = message.toolCallId,
            toolCalls = message.toolCalls,
            thoughtSignature = message.thoughtSignature,
            files = message.files
        )
}
