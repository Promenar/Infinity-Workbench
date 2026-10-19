package com.promenar.nexara.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 风险行只来自结构化事实与说明中的风险句；风险类别标签在标题行单独展示，不进入风险行。 */
class ApprovalRiskSummaryTest {
    @Test
    fun `元信息行目标与工具 ID 相同或为空时不重复显示`() {
        assertEquals(null, approvalHeaderTarget("python_sandbox", "python_sandbox"))
        assertEquals(null, approvalHeaderTarget("python_sandbox", "Python_Sandbox"))
        assertEquals(null, approvalHeaderTarget("write_file", " "))
        assertEquals(null, approvalHeaderTarget("write_file", null))
        assertEquals("/notes/meeting.md", approvalHeaderTarget("write_file", "/notes/meeting.md"))
        assertEquals("builtin", approvalHeaderTarget("shell_exec", "builtin"))
    }

    @Test
    fun `执行模式不能遮掉不可恢复或可联网的风险事实`() {
        assertEquals(listOf("不可恢复"), approvalRiskLines("Execution mode: semi", listOf("不可恢复")))
        assertEquals(listOf("可联网"), approvalRiskLines("Execution mode: semi", listOf("可联网")))
        assertEquals(emptyList<String>(), approvalRiskLines("Execution mode: semi", emptyList()))
    }

    @Test
    fun `普通请求原因与参数不产生风险行`() {
        assertEquals(emptyList<String>(), approvalRiskLines("Update workspace", emptyList()))
        assertEquals(emptyList<String>(), approvalRiskLines("Update workspace\nArguments: {\"network\":true}", emptyList()))
        assertEquals(emptyList<String>(), approvalRiskLines("Execution mode: semi\n参数：{\"path\":\"old.md\"}", emptyList()))
    }

    @Test
    fun `事实与说明中的风险句逐条保留且去重`() {
        assertEquals(listOf("可联网"), approvalRiskLines("请核对脚本。", listOf("可联网", "可联网")))
        assertEquals(listOf("可联网"), approvalRiskLines("可联网", listOf("可联网")))
        assertEquals(listOf("不可恢复", "此操作会修改文件。"), approvalRiskLines("此操作会修改文件。", listOf("不可恢复")))
    }

    @Test
    fun `请求原因保留普通内容并过滤执行模式参数与风险语句`() {
        assertEquals("Update workspace", approvalRequestReason("Execution mode: semi\nUpdate workspace\nArguments: {}"))
        assertEquals("核对目标", approvalRequestReason("核对目标\n此操作会删除文件且不可恢复。"))
        assertEquals(listOf("This can delete files irreversibly."), approvalRiskLines("This can delete files irreversibly.", emptyList()))
    }
}
