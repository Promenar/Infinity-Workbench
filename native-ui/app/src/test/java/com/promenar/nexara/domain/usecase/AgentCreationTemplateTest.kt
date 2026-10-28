package com.promenar.nexara.domain.usecase

import com.promenar.nexara.data.agent.AgentRetrievalMode
import com.promenar.nexara.data.agent.KnowledgeFileAccess
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.repository.IAgentRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCreationTemplateTest {

    @Test
    fun `chat template matches the decision table`() {
        val template = AgentCreationType.CHAT.template()
        assertFalse(template.toolsEnabledByDefault)
        assertEquals(AgentRetrievalMode.AUTO, template.retrievalMode)
        assertEquals(KnowledgeFileAccess.OFF, template.knowledgeFileAccess)
    }

    @Test
    fun `agent template matches the decision table`() {
        val template = AgentCreationType.AGENT.template()
        assertTrue(template.toolsEnabledByDefault)
        assertEquals(AgentRetrievalMode.TOOL, template.retrievalMode)
        assertEquals(KnowledgeFileAccess.ON_REQUEST, template.knowledgeFileAccess)
    }

    @Test
    fun `created chat agent stores template parameters as plain settings`() = runTest {
        val created = createWith(AgentCreationType.CHAT)
        assertFalse(created.toolsEnabledByDefault)
        assertEquals(AgentRetrievalMode.AUTO, created.retrievalConfig?.retrievalMode)
        assertEquals(KnowledgeFileAccess.OFF, created.retrievalConfig?.knowledgeFileAccess)
    }

    @Test
    fun `created agent type stores template parameters as plain settings`() = runTest {
        val created = createWith(AgentCreationType.AGENT)
        assertTrue(created.toolsEnabledByDefault)
        assertEquals(AgentRetrievalMode.TOOL, created.retrievalConfig?.retrievalMode)
        assertEquals(KnowledgeFileAccess.ON_REQUEST, created.retrievalConfig?.knowledgeFileAccess)
    }

    @Test
    fun `creating without a template keeps previous defaults`() = runTest {
        val created = createWith(null)
        assertTrue(created.toolsEnabledByDefault)
        assertNull(created.retrievalConfig)
    }

    private suspend fun createWith(type: AgentCreationType?): Agent {
        val repository = mockk<IAgentRepository>(relaxed = true)
        val captured = slot<Agent>()
        coEvery { repository.create(capture(captured)) } returns Unit
        CreateAgentUseCase(repository)("Agent", "", "model", "", type = type)
        return captured.captured
    }
}
