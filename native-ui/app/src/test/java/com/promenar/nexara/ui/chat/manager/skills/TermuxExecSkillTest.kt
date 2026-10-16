package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxCommandRequest
import com.promenar.nexara.data.termux.TermuxCommandResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class TermuxExecSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun `工具为PROCESS且参数保留stdin默认超时并回传结构化结果`() = runTest {
        var captured: TermuxCommandRequest? = null
        val skill = TermuxExecSkill({ captured = it; TermuxCommandResult(stdout = "done", exitCode = 0) }, { true })
        assertThat(skill.risk).isEqualTo(ToolRisk.PROCESS)
        val result = skill.execute(args("""{"command":"cat","stdin":"text\\n","workdir":"/tmp"}"""), context)
        assertThat(captured!!.command).isEqualTo("cat")
        assertThat(captured!!.timeoutSeconds).isEqualTo(60)
        assertThat(captured!!.workdir).isEqualTo("/tmp")
        assertThat(captured!!.stdin).isEqualTo("text\\n")
        assertThat(result.status).isEqualTo("success")
        assertThat(result.content).contains("done")
    }

    @Test fun `关闭集成和非法参数不派发且超时作为工具错误回传`() = runTest {
        var dispatched = 0
        val runner: suspend (TermuxCommandRequest) -> TermuxCommandResult = {
            dispatched++
            TermuxCommandResult(errorCode = "TERMUX_TIMEOUT", timedOut = true)
        }
        val disabled = TermuxExecSkill(runner, { false })
        assertThat(disabled.execute(args("""{"command":"id"}"""), context).status).isEqualTo("error")
        val enabled = TermuxExecSkill(runner, { true })
        listOf("{}", """{"command":"id","timeout_seconds":601}""", """{"command":"id","stdin":42}""", """{"command":"id","host":"remote"}""").forEach {
            assertThat(enabled.execute(args(it), context).status).isEqualTo("error")
        }
        assertThat(dispatched).isEqualTo(0)
        val timeout = enabled.execute(args("""{"command":"id","timeout_seconds":600}"""), context)
        assertThat(dispatched).isEqualTo(1)
        assertThat(timeout.status).isEqualTo("error")
        assertThat(timeout.content).contains("TERMUX_TIMEOUT")
    }
}
