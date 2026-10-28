package com.promenar.nexara.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.Test

class NewSessionDefaultsTest {
    @Test fun `会话初值只采用全局开关及智能体记忆默认与工作区`() {
        val agent = Agent("a", "智能体", modelId = "agent-model", executionMode = ExecutionMode.FULL_ACCESS,
            skills = listOf("custom"), mcpServerIds = listOf("mcp"), defaultWorkspaceId = "workspace",
            useInheritedConfig = false, retrievalConfig = AgentRetrievalConfig(enableMemory = true,
                memoryPolicy = AgentMemoryPolicy(memoryDefault = ConversationScope.ALL_AGENTS, memoryCap = ConversationScope.SESSION)))
        val session = newSessionDefaults("s", "a", agent, "fallback", RagConfiguration(enableMemory = false, enableKnowledgeGraph = true), 42)
        assertThat(session.modelId).isEqualTo("agent-model")
        assertThat(session.executionMode).isEqualTo("full_access")
        assertThat(session.activeSkillIds).containsExactly("custom")
        assertThat(session.activeMcpServerIds).containsExactly("mcp")
        assertThat(session.workspaceId).isEqualTo("workspace")
        assertThat(session.ragOptions!!.enableMemory).isFalse()
        assertThat(session.ragOptions!!.enableKnowledgeGraph).isTrue()
        assertThat(session.ragOptions!!.memoryScope).isEqualTo(ConversationScope.SESSION)
        assertThat(session.inferenceParams).isNull()
        assertThat(session.createdAt).isEqualTo(42)
        assertThat(session.updatedAt).isEqualTo(42)
        assertThat(newSessionDefaults("s", "missing", null, "fallback", RagConfiguration(), 42).modelId).isEqualTo("fallback")
        assertThat(newSessionDefaults("s", "a", agent.copy(modelId = " "), "fallback", RagConfiguration(), 42).modelId).isEqualTo("fallback")
    }
}
