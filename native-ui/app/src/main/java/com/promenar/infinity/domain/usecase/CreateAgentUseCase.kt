package com.promenar.infinity.domain.usecase

import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.avatar.AgentAvatarCodec
import com.promenar.infinity.domain.avatar.AgentAvatarPalette

class CreateAgentUseCase(
    private val agentRepository: IAgentRepository
) {
    suspend operator fun invoke(
        name: String,
        description: String,
        modelId: String,
        systemPrompt: String,
        icon: String = AgentAvatarCodec.NEW_AGENT_DEFAULT,
        color: String? = null,
        type: AgentCreationType? = null,
    ): Agent {
        require(name.isNotBlank()) { "Agent name cannot be blank" }
        val template = type?.template()
        val id = IdGenerator.agent()
        val agent = Agent(
            id = id,
            name = name.trim(),
            description = description,
            systemPrompt = systemPrompt,
            modelId = modelId,
            icon = icon,
            color = color ?: AgentAvatarPalette.forSeed(id).hex,
            executionMode = ExecutionMode.WORKSPACE,
            retrievalConfig = template?.retrievalConfig(),
            toolsEnabledByDefault = template?.toolsEnabledByDefault ?: true,
            createdAt = System.currentTimeMillis()
        )
        agentRepository.create(agent)
        return agent
    }
}
