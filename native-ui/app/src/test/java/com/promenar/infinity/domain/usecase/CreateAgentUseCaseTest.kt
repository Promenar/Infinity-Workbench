package com.promenar.infinity.domain.usecase

import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.repository.IAgentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreateAgentUseCaseTest {

    private fun createUseCase(repository: IAgentRepository = mockk(relaxed = true)): CreateAgentUseCase {
        return CreateAgentUseCase(repository)
    }

    @Test
    fun `invoke creates agent with provided fields`() = runTest {
        val repository = mockk<IAgentRepository>(relaxed = true)
        val agentSlot = slot<Agent>()
        coEvery { repository.create(capture(agentSlot)) } returns Unit

        val useCase = createUseCase(repository)
        val result = useCase(
            name = "Test Agent",
            description = "A test agent",
            modelId = "gpt-4",
            systemPrompt = "Be helpful"
        )

        val captured = agentSlot.captured
        assertEquals("Test Agent", captured.name)
        assertEquals("A test agent", captured.description)
        assertEquals("gpt-4", captured.modelId)
        assertEquals("Be helpful", captured.systemPrompt)
        assertEquals(ExecutionMode.WORKSPACE, captured.executionMode)
        assertTrue(captured.id.startsWith("agent_"))
        assertNotNull(captured.createdAt)
        coVerify(exactly = 1) { repository.create(any()) }
    }

    @Test
    fun `invoke returns created agent`() = runTest {
        val useCase = createUseCase()
        val result = useCase(
            name = "Test Agent",
            description = "desc",
            modelId = "model",
            systemPrompt = "prompt"
        )
        assertEquals("Test Agent", result.name)
        assertTrue(result.id.startsWith("agent_"))
    }

    @Test
    fun `invoke trims name`() = runTest {
        val repository = mockk<IAgentRepository>(relaxed = true)
        val agentSlot = slot<Agent>()
        coEvery { repository.create(capture(agentSlot)) } returns Unit

        val useCase = createUseCase(repository)
        useCase(name = "  Trimmed  ", description = "", modelId = "", systemPrompt = "")

        assertEquals("Trimmed", agentSlot.captured.name)
    }

    @Test
    fun `invoke throws on blank name`() {
        val useCase = createUseCase()
        assertThrows<IllegalArgumentException> {
            kotlinx.coroutines.test.runTest {
                useCase(name = "   ", description = "", modelId = "", systemPrompt = "")
            }
        }
    }

    @Test
    fun `invoke throws on empty name`() {
        val useCase = createUseCase()
        assertThrows<IllegalArgumentException> {
            kotlinx.coroutines.test.runTest {
                useCase(name = "", description = "", modelId = "", systemPrompt = "")
            }
        }
    }

    @Test
    fun `invoke uses default icon and color`() = runTest {
        val repository = mockk<IAgentRepository>(relaxed = true)
        val agentSlot = slot<Agent>()
        coEvery { repository.create(capture(agentSlot)) } returns Unit

        val useCase = createUseCase(repository)
        useCase(name = "Test", description = "", modelId = "", systemPrompt = "")

        assertEquals("gen:", agentSlot.captured.icon)
        assertEquals(com.promenar.infinity.domain.avatar.AgentAvatarPalette.forSeed(agentSlot.captured.id).hex, agentSlot.captured.color)
    }

    @Test
    fun `只传图标时颜色按新id播种且领域实体默认一致`() = runTest {
        val result = createUseCase()("Test", "", "", "", icon = "sym:terminal")
        assertEquals("sym:terminal", result.icon)
        assertEquals(com.promenar.infinity.domain.avatar.AgentAvatarPalette.forSeed(result.id).hex, result.color)
        val domain = Agent(id = "new-id", name = "新建")
        val entity = com.promenar.infinity.data.local.db.entity.AgentEntity(id = "new-id", name = "新建", createdAt = 1)
        assertEquals("gen:", domain.icon)
        assertEquals(domain.icon, entity.icon)
        assertEquals(domain.color, entity.color)
        val colorOnly = createUseCase()("Test", "", "", "", color = "#123456")
        assertEquals("gen:", colorOnly.icon)
        assertEquals("#123456", colorOnly.color)
    }

    @Test
    fun `invoke uses custom icon and color`() = runTest {
        val repository = mockk<IAgentRepository>(relaxed = true)
        val agentSlot = slot<Agent>()
        coEvery { repository.create(capture(agentSlot)) } returns Unit

        val useCase = createUseCase(repository)
        useCase(name = "Test", description = "", modelId = "", systemPrompt = "", icon = "🚀", color = "#FF0000")

        assertEquals("🚀", agentSlot.captured.icon)
        assertEquals("#FF0000", agentSlot.captured.color)
    }
}
