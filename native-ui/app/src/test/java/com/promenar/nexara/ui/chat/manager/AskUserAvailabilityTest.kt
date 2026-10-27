package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.subagent.SubagentLimits
import com.promenar.nexara.data.subagent.SubagentToolProfile
import com.promenar.nexara.domain.tool.ToolRisk
import org.junit.Test

class AskUserAvailabilityTest {
    @Test fun `子代理所有工具档位都排除向用户提问`() {
        val tool = ProtocolTool(function = ProtocolToolFunction("ask_user", "提问", "{}"),
            runtimeToolId = "ask_user", sourceId = "builtin", risk = ToolRisk.SAFE_READ)
        SubagentToolProfile.entries.forEach { profile ->
            assertThat(SubagentLimits.toolset(listOf(tool), profile)).isEmpty()
        }
    }

    @Test fun `提问指引要求单独调用并说明同批调用不会执行`() {
        val guidance = SystemPromptSections.toolGuidance(setOf("ask_user"), generic = true)
        assertThat(guidance).contains("单独调用")
        assertThat(guidance).contains("其它调用")
    }
}
