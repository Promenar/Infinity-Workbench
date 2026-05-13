package com.promenar.nexara.domain.model

import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig

data class Agent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: String = "",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    val avatarPath: String? = null,
    val isPinned: Boolean = false,
    val temperature: Double? = 0.7,
    val topP: Double? = 0.9,
    val maxTokens: Int? = 4096,
    val ragConfig: AgentRagConfig? = null,
    val retrievalConfig: AgentRetrievalConfig? = null,
    val useInheritedConfig: Boolean = true,
    val executionMode: ExecutionMode = ExecutionMode.SEMI,
    val skills: List<String> = emptyList(),
    val createdAt: Long = 0L
)
