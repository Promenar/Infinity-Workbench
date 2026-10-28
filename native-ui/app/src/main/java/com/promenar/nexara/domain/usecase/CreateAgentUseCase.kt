package com.promenar.nexara.domain.usecase

import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.repository.IAgentRepository

class CreateAgentUseCase(
    private val agentRepository: IAgentRepository
) {
    suspend operator fun invoke(
        name: String,
        description: String,
        modelId: String,
        systemPrompt: String,
        icon: String = "✨",
        color: String = "#C0C1FF",
        type: AgentCreationType? = null,
    ): Agent {
        require(name.isNotBlank()) { "Agent name cannot be blank" }
        val template = type?.template()
        val agent = Agent(
            id = IdGenerator.agent(),
            name = name.trim(),
            description = description,
            systemPrompt = systemPrompt,
            modelId = modelId,
            icon = icon,
            color = color,
            executionMode = ExecutionMode.WORKSPACE,
            retrievalConfig = template?.retrievalConfig(),
            toolsEnabledByDefault = template?.toolsEnabledByDefault ?: true,
            createdAt = System.currentTimeMillis()
        )
        agentRepository.create(agent)
        return agent
    }
}
