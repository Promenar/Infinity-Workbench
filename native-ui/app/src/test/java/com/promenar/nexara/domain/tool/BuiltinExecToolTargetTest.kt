package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.ui.chat.ApprovalPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class BuiltinExecToolTargetTest {
    private fun target(name: String, args: String, sourceId: String = "builtin") = ToolExecutionTargets.extract(
        ProtocolTool(function = ProtocolToolFunction(name, "", "{}"), risk = ToolRisk.PROCESS, sourceId = sourceId), args,
    )

    @Test fun `shell_exec 审批绑定内置后端与完整命令`() {
        assertThat(target("shell_exec", """{"command":"rm -rf build","timeout_seconds":5}"""))
            .isEqualTo("""["process","builtin","rm -rf build"]""")
        assertThat(target("shell_exec", """{"command":"ls","host":"evil"}"""))
            .isEqualTo("""["process","builtin","ls"]""")
        assertThat(target("shell_exec", """{"command":"  "}""")).isNull()
        assertThat(ToolExecutionTargets.display("""["process","builtin","ls -la"]""")).isEqualTo("builtin · ls -la")
        assertThat(ToolExecutionTargets.processScope("""["process","builtin","ls -la"]""")).isEqualTo("builtin")
        assertThat(ToolExecutionTargets.processScope("""["process","termux","ls",{"sshHostId":"h"}]""")).isEqualTo("termux")
        assertThat(ToolExecutionTargets.processScope("""["path","/a"]""")).isNull()
        assertThat(ToolExecutionTargets.processScope("not json")).isNull()
    }

    @Test fun `python_exec 审批绑定完整代码`() {
        assertThat(target("python_exec", """{"code":"print(1)"}"""))
            .isEqualTo("""["process","python","print(1)"]""")
        assertThat(target("python_exec", """{"command":"print(1)"}""")).isNull()
    }

    @Test fun `非内置来源的同名工具不借用内置后端身份`() {
        assertThat(target("shell_exec", """{"command":"ls"}""", sourceId = "user")).isNull()
        assertThat(target("python_exec", """{"code":"1"}""", sourceId = "user")).isNull()
    }

    @Test fun `审批预览展示命令正文并标记工作区风险`() {
        val shell = ApprovalPreview.format("shell_exec", Json.parseToJsonElement("""{"command":"echo a\necho b"}""").jsonObject)
        assertThat(shell?.action).isEqualTo(ApprovalPreview.Action.SCRIPT)
        assertThat(shell?.detail).isEqualTo("echo a\necho b")
        assertThat(shell?.commandRunsInWorkspace).isTrue()
        val python = ApprovalPreview.format("python_exec", Json.parseToJsonElement("""{"code":"import os"}""").jsonObject)
        assertThat(python?.detail).isEqualTo("import os")
        assertThat(python?.commandRunsInWorkspace).isTrue()
        assertThat(ApprovalPreview.format("termux_exec", Json.parseToJsonElement("""{"command":"ls"}""").jsonObject)).isNull()
    }
}
