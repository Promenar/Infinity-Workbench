package com.promenar.nexara.domain.usecase

import android.content.SharedPreferences
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.domain.model.Agent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentConfigResolverTest {

    private fun createPrefs(
        defaultModel: String? = null,
        defaultTemperature: Float? = null,
        defaultTopP: Float? = null,
        defaultMaxTokens: Int? = null
    ): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("default_model", any()) } returns (defaultModel ?: "")
        every { prefs.getFloat("default_temperature", any()) } returns (defaultTemperature ?: 0.7f)
        every { prefs.getFloat("default_top_p", any()) } returns (defaultTopP ?: 0.9f)
        every { prefs.getInt("default_max_tokens", any()) } returns (defaultMaxTokens ?: 4096)
        return prefs
    }

    @Test
    fun `resolve returns agent fields when agent is fully populated`() {
        val prefs = createPrefs()
        val resolver = AgentConfigResolver(prefs)
        val ragConfig = AgentRagConfig()
        val retrievalConfig = AgentRetrievalConfig()
        val agent = Agent(
            id = "agent_1",
            name = "Test",
            systemPrompt = "You are helpful",
            modelId = "gpt-4",
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            ragConfig = ragConfig,
            retrievalConfig = retrievalConfig
        )

        val config = resolver.resolve(agent)

        assertEquals("You are helpful", config.systemPrompt)
        assertEquals("gpt-4", config.modelId)
        assertEquals(0.5, config.temperature)
        assertEquals(0.8, config.topP)
        assertEquals(2048, config.maxTokens)
        assertEquals(ragConfig, config.ragConfig)
        assertEquals(retrievalConfig, config.retrievalConfig)
    }

    @Test
    fun `resolve falls back to global prefs when agent is null`() {
        val prefs = createPrefs(defaultModel = "global-model", defaultTemperature = 0.3f, defaultTopP = 0.5f, defaultMaxTokens = 8192)
        val resolver = AgentConfigResolver(prefs)

        val config = resolver.resolve(null)

        assertEquals("", config.systemPrompt)
        assertEquals("global-model", config.modelId)
        assertEquals(0.3f.toDouble(), config.temperature, 0.001)
        assertEquals(0.5f.toDouble(), config.topP, 0.001)
        assertEquals(8192, config.maxTokens)
        assertNull(config.ragConfig)
        assertNull(config.retrievalConfig)
    }

    @Test
    fun `resolve falls back to global prefs for null agent fields`() {
        val prefs = createPrefs(defaultModel = "global-model")
        val resolver = AgentConfigResolver(prefs)
        val agent = Agent(
            id = "agent_1",
            name = "Test",
            temperature = null,
            topP = null,
            maxTokens = null
        )

        val config = resolver.resolve(agent)

        assertEquals("", config.modelId)
        assertEquals(0.7, config.temperature, 0.001)
        assertEquals(0.9, config.topP, 0.001)
        assertEquals(4096, config.maxTokens)
    }

    @Test
    fun `resolve uses global defaults when both agent and prefs have no values`() {
        val prefs = createPrefs()
        val resolver = AgentConfigResolver(prefs)

        val config = resolver.resolve(null)

        assertEquals("", config.modelId)
        assertEquals(0.7, config.temperature, 0.001)
        assertEquals(0.9, config.topP, 0.001)
        assertEquals(4096, config.maxTokens)
    }

    @Test
    fun `resolveName returns agent name when agent exists`() {
        val resolver = AgentConfigResolver(createPrefs())
        val agent = Agent(id = "agent_1", name = "My Agent")
        assertEquals("My Agent", resolver.resolveName(agent))
    }

    @Test
    fun `resolveName returns empty string when agent is null`() {
        val resolver = AgentConfigResolver(createPrefs())
        assertEquals("", resolver.resolveName(null))
    }

    @Test
    fun `resolve preserves rag and retrieval config as null when agent has none`() {
        val prefs = createPrefs()
        val resolver = AgentConfigResolver(prefs)
        val agent = Agent(id = "agent_1", name = "Test")

        val config = resolver.resolve(agent)

        assertNull(config.ragConfig)
        assertNull(config.retrievalConfig)
    }

    @Test
    fun `resolve uses agent system prompt even when global exists`() {
        val prefs = createPrefs()
        val resolver = AgentConfigResolver(prefs)
        val agent = Agent(id = "agent_1", name = "Test", systemPrompt = "Agent prompt")

        val config = resolver.resolve(agent)

        assertEquals("Agent prompt", config.systemPrompt)
    }

    @Test
    fun `resolve uses agent model when agent has one`() {
        val prefs = createPrefs(defaultModel = "global-model")
        val resolver = AgentConfigResolver(prefs)
        val agent = Agent(id = "agent_1", name = "Test", modelId = "agent-model")

        val config = resolver.resolve(agent)

        assertEquals("agent-model", config.modelId)
    }
}
