package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.ui.chat.ApprovalPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ScriptToolTargetTest {
    private fun target(name: String, args: String) = ToolExecutionTargets.extract(
        ProtocolTool(function = ProtocolToolFunction(name, "", "{}"), risk = ToolRisk.SCRIPT), args,
    )

    @Test fun `脚本文件目标使用规范化路径`() {
        assertThat(target("run_script", """{"path":"mnt/demo//main.mjs"}"""))
            .isEqualTo("""["path","/mnt/demo/main.mjs"]""")
        assertThat(target("run_script", """{"path":"/docs/../secret.js"}""")).isNull()
        assertThat(target("run_script", """{"path":"/.infinity/a.js"}""")).isNull()
    }

    @Test fun `内联脚本目标保持工具名兼容`() {
        assertThat(target("exec_js", """{"code":"1 + 2","allow_network":true}"""))
            .isEqualTo("""["tool","exec_js"]""")
        assertThat(target("python_sandbox", """{"code":"print(1)","allow_network":true}"""))
            .isEqualTo("""["tool","python_sandbox"]""")
        assertThat(target("python_sandbox", "{}")).isNull()
    }

    @Test fun `两种联网脚本审批均显式提示可联网`() {
        listOf("exec_js", "run_script", "python_sandbox").forEach { name ->
            val args = Json.parseToJsonElement("""{"code":"1","path":"/a.js","allow_network":true}""").jsonObject
            assertThat(ApprovalPreview.format(name, args)?.detail).contains("可联网")
        }
    }
}
