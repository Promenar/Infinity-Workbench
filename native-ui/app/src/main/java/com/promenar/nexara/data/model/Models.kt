package com.promenar.nexara.data.model

data class Agent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val model: String = "gpt-4o",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    val isPinned: Boolean = false,
    val createdAt: Long = 0L
)

data class ChatSession(
    val id: String,
    val agentId: String,
    val title: String = "新会话",
    val lastMessage: String = "",
    val lastUpdatedAt: Long = 0L
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

data class TokenUsage(
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0
)

data class GeneratedImageData(
    val thumbnail: String,
    val original: String,
    val mime: String
)

data class Citation(
    val title: String,
    val url: String,
    val source: String? = null
)
