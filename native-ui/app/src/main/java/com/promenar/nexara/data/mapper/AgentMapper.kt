package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode

object AgentMapper {
    fun toDomain(entity: AgentEntity): Agent = Agent(
        id = entity.id,
        name = entity.name,
        description = entity.description,
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
        executionMode = ExecutionMode.SEMI,
        skills = emptyList(),
        createdAt = entity.createdAt
    )

    fun toEntity(agent: Agent): AgentEntity = AgentEntity(
        id = agent.id,
        name = agent.name,
        description = agent.description,
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
        createdAt = agent.createdAt
    )
}
