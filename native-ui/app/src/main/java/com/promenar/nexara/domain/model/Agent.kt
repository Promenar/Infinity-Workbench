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
    val mcpServerIds: List<String> = emptyList(),
    val nameCustomized: Boolean = false,
    val descriptionCustomized: Boolean = false,
    val createdAt: Long = 0L
)

object ExecutionModeCodec {
    fun parseOrSemi(raw: String?): ExecutionMode = when (raw?.trim()?.lowercase()) {
        "auto" -> ExecutionMode.AUTO
        "manual" -> ExecutionMode.MANUAL
        "semi" -> ExecutionMode.SEMI
        else -> ExecutionMode.SEMI
    }

    fun serialize(mode: ExecutionMode): String = mode.name.lowercase()
}
