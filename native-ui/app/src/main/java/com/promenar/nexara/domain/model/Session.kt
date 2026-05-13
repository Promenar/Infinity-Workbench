package com.promenar.nexara.domain.model

data class Session(
    val id: String,
    val agentId: String,
    val title: String,
    val modelId: String,
    val isPinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val messageCount: Int = 0
)
