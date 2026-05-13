package com.promenar.nexara.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val ragReferences: List<RagReference>? = null,
    val tokenUsage: TokenUsage? = null,
    val timestamp: Long = 0L
)
