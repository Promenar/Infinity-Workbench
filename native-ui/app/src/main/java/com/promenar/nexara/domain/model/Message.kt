package com.promenar.nexara.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val modelId: String? = null,
    val documents: List<MessageDocumentAttachment> = emptyList(),
    val thinking: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val ragReferences: List<RagReference>? = null,
    val tokenUsage: TokenUsage? = null,
    val timestamp: Long = 0L
)

data class MessageDocumentAttachment(
    val name: String,
    val mimeType: String,
    val content: String,
)
