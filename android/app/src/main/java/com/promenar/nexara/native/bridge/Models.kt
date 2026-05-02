package com.promenar.nexara.native.bridge

/**
 * 对应 JS 端的 Agent 类型
 */
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

/**
 * 对应 JS 端的 Session 类型
 */
data class ChatSession(
    val id: String,
    val agentId: String,
    val title: String = "新会话",
    val lastMessage: String = "",
    val lastUpdatedAt: Long = 0L
)
