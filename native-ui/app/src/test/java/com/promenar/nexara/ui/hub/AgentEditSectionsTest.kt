package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.jupiter.api.Test

class AgentEditSectionsTest {
    private fun server(id: String, name: String = id.uppercase(), enabled: Boolean = true) =
        McpServerEntity(id = id, name = name, url = "https://$id.example", enabled = enabled)

    @Test
    fun `MCP 行先列可选服务器并标记已选`() {
        val rows = agentMcpRows(listOf(server("a"), server("b")), selectedIds = listOf("b")) { it.enabled }
        assertThat(rows).containsExactly(
            AgentMcpRow("a", "A", checked = false, available = true),
            AgentMcpRow("b", "B", checked = true, available = true),
        ).inOrder()
    }

    @Test
    fun `已选但已停用或已删除的服务器追加为不可用行，名称缺失时显示 id`() {
        val rows = agentMcpRows(
            listOf(server("a"), server("off", name = "Off", enabled = false)),
            selectedIds = listOf("off", "gone", "gone"),
        ) { it.enabled }
        assertThat(rows).containsExactly(
            AgentMcpRow("a", "A", checked = false, available = true),
            AgentMcpRow("off", "Off", checked = true, available = false),
            AgentMcpRow("gone", "gone", checked = true, available = false),
        ).inOrder()
    }

    @Test
    fun `默认选项只有一个且与运行时默认一致`() {
        val defaults = AgentRetrievalConfig()
        assertThat(retrievalModeChoices.single { it.isDefault }.value).isEqualTo(defaults.retrievalMode)
        assertThat(fileAccessChoices.single { it.isDefault }.value).isEqualTo(defaults.knowledgeFileAccess)
        assertThat(executionModeChoices.single { it.isDefault }.value).isEqualTo(ExecutionMode.WORKSPACE)
    }
}
