package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.domain.model.ExecutionMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AgentMapperTest {

    private fun createEntity(
        id: String = "test-id",
        name: String = "Test Agent",
        description: String = "desc",
        systemPrompt: String = "prompt",
        model: String = "gpt-4",
        icon: String = "✨",
        color: String = "#FF0000",
        avatarPath: String? = "/path/avatar.png",
        isPinned: Int = 1,
        temperature: Double? = 0.7,
        topP: Double? = 0.9,
        maxTokens: Int? = 4096,
        ragConfig: com.promenar.nexara.data.agent.AgentRagConfig? = null,
        retrievalConfig: com.promenar.nexara.data.agent.AgentRetrievalConfig? = null,
        useInheritedConfig: Boolean = true,
        createdAt: Long = 1000L
    ) = AgentEntity(
        id = id,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        model = model,
        icon = icon,
        color = color,
        avatarPath = avatarPath,
        isPinned = isPinned,
        temperature = temperature,
        top_p = topP,
        max_tokens = maxTokens,
        ragConfig = ragConfig,
        retrievalConfig = retrievalConfig,
        useInheritedConfig = useInheritedConfig,
        createdAt = createdAt
    )

    @Test
    fun `toDomain maps all fields correctly`() {
        val entity = createEntity()
        val agent = AgentMapper.toDomain(entity)
        assertThat(agent.id).isEqualTo("test-id")
        assertThat(agent.name).isEqualTo("Test Agent")
        assertThat(agent.description).isEqualTo("desc")
        assertThat(agent.systemPrompt).isEqualTo("prompt")
        assertThat(agent.modelId).isEqualTo("gpt-4")
        assertThat(agent.icon).isEqualTo("✨")
        assertThat(agent.color).isEqualTo("#FF0000")
        assertThat(agent.avatarPath).isEqualTo("/path/avatar.png")
        assertThat(agent.isPinned).isTrue()
        assertThat(agent.temperature).isEqualTo(0.7)
        assertThat(agent.topP).isEqualTo(0.9)
        assertThat(agent.maxTokens).isEqualTo(4096)
        assertThat(agent.useInheritedConfig).isTrue()
        assertThat(agent.createdAt).isEqualTo(1000L)
    }

    @Test
    fun `toDomain handles isPinned=0`() {
        val entity = createEntity(isPinned = 0)
        assertThat(AgentMapper.toDomain(entity).isPinned).isFalse()
    }

    @Test
    fun `toDomain defaults executionMode to SEMI`() {
        val entity = createEntity()
        assertThat(AgentMapper.toDomain(entity).executionMode).isEqualTo(ExecutionMode.SEMI)
    }

    @Test
    fun `toDomain defaults skills to empty`() {
        val entity = createEntity()
        assertThat(AgentMapper.toDomain(entity).skills).isEmpty()
    }

    @Test
    fun `toDomain handles null optional fields`() {
        val entity = createEntity(
            avatarPath = null,
            temperature = null,
            topP = null,
            maxTokens = null,
            ragConfig = null,
            retrievalConfig = null
        )
        val agent = AgentMapper.toDomain(entity)
        assertThat(agent.avatarPath).isNull()
        assertThat(agent.temperature).isNull()
        assertThat(agent.topP).isNull()
        assertThat(agent.maxTokens).isNull()
    }

    @Test
    fun `toEntity maps domain to entity correctly`() {
        val agent = com.promenar.nexara.domain.model.Agent(
            id = "e1",
            name = "Entity Test",
            description = "ed",
            systemPrompt = "sp",
            modelId = "m1",
            icon = "🧪",
            color = "#ABC",
            isPinned = true,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            createdAt = 42L
        )
        val entity = AgentMapper.toEntity(agent)
        assertThat(entity.id).isEqualTo("e1")
        assertThat(entity.name).isEqualTo("Entity Test")
        assertThat(entity.model).isEqualTo("m1")
        assertThat(entity.isPinned).isEqualTo(1)
        assertThat(entity.temperature).isEqualTo(0.5)
        assertThat(entity.top_p).isEqualTo(0.8)
        assertThat(entity.max_tokens).isEqualTo(2048)
        assertThat(entity.createdAt).isEqualTo(42L)
    }

    @Test
    fun `toEntity maps isPinned=false to 0`() {
        val agent = com.promenar.nexara.domain.model.Agent(id = "x", name = "n", isPinned = false)
        assertThat(AgentMapper.toEntity(agent).isPinned).isEqualTo(0)
    }

    @Test
    fun `roundtrip preserves core fields`() {
        val entity = createEntity(
            id = "roundtrip",
            name = "RT",
            isPinned = 1,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            createdAt = 42L
        )
        val domain = AgentMapper.toDomain(entity)
        val back = AgentMapper.toEntity(domain)
        assertThat(back.id).isEqualTo(entity.id)
        assertThat(back.name).isEqualTo(entity.name)
        assertThat(back.isPinned).isEqualTo(entity.isPinned)
        assertThat(back.temperature).isEqualTo(entity.temperature)
        assertThat(back.top_p).isEqualTo(entity.top_p)
        assertThat(back.max_tokens).isEqualTo(entity.max_tokens)
        assertThat(back.createdAt).isEqualTo(entity.createdAt)
    }
}
