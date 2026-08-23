package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionModeCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val agentSelectionJson = Json { ignoreUnknownKeys = true }

private fun decodeOrderedIds(raw: String?): List<String> = runCatching {
    raw?.let { agentSelectionJson.decodeFromString<List<String>>(it) }.orEmpty()
}.getOrDefault(emptyList())

private fun encodeOrderedIds(ids: List<String>): String = agentSelectionJson.encodeToString(ids)

object AgentMapper {
    fun toDomain(entity: AgentEntity): Agent = Agent(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        nameCustomized = entity.nameCustomized,
        descriptionCustomized = entity.descriptionCustomized,
        systemPrompt = entity.systemPrompt,
        modelId = entity.model,
        icon = entity.icon,
        color = entity.color,
        avatarPath = entity.avatarPath,
        isPinned = entity.isPinned != 0,
        temperature = entity.temperature,
        topP = entity.top_p,
        maxTokens = entity.max_tokens,
        ragConfig = entity.ragConfig,
        retrievalConfig = entity.retrievalConfig,
        useInheritedConfig = entity.useInheritedConfig,
        executionMode = ExecutionModeCodec.parseOrSemi(entity.executionMode),
        skills = decodeOrderedIds(entity.skillIds),
        mcpServerIds = decodeOrderedIds(entity.mcpServerIds),
        createdAt = entity.createdAt
    )

    fun toEntity(agent: Agent): AgentEntity = AgentEntity(
        id = agent.id,
        name = agent.name,
        description = agent.description,
        nameCustomized = agent.nameCustomized,
        descriptionCustomized = agent.descriptionCustomized,
        systemPrompt = agent.systemPrompt,
        model = agent.modelId,
        icon = agent.icon,
        color = agent.color,
        avatarPath = agent.avatarPath,
        isPinned = if (agent.isPinned) 1 else 0,
        temperature = agent.temperature,
        top_p = agent.topP,
        max_tokens = agent.maxTokens,
        ragConfig = agent.ragConfig,
        retrievalConfig = agent.retrievalConfig,
        useInheritedConfig = agent.useInheritedConfig,
        executionMode = ExecutionModeCodec.serialize(agent.executionMode),
        skillIds = encodeOrderedIds(agent.skills),
        mcpServerIds = encodeOrderedIds(agent.mcpServerIds),
        createdAt = agent.createdAt
    )
}
