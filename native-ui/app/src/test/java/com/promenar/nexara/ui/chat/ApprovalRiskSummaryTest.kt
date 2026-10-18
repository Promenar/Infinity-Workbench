package com.promenar.nexara.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalRiskSummaryTest {
    @Test
    fun `执行模式不能遮掉不可恢复或可联网的风险事实`() {
        assertEquals("不可恢复", approvalRiskSummary("Execution mode: semi", "删除", listOf("不可恢复")))
        assertEquals("可联网", approvalRiskSummary("Execution mode: semi", "脚本", listOf("可联网")))
        assertEquals("文件写入", approvalRiskSummary("Execution mode: semi", "文件写入", emptyList()))
    }

    @Test
    fun `普通请求原因与参数不能顶替风险标签`() {
        assertEquals("文件写入", approvalRiskSummary("Update workspace", "文件写入", emptyList()))
        assertEquals("文件写入", approvalRiskSummary("Update workspace\nArguments: {\"network\":true}", "文件写入", emptyList()))
        assertEquals("删除", approvalRiskSummary("Execution mode: semi\n参数：{\"path\":\"old.md\"}", "删除", emptyList()))
    }

    @Test
    fun `说明句与关键事实合为一段且不重复风险标签或相同说明`() {
        assertEquals("可联网", approvalRiskSummary("请核对脚本。", "脚本", listOf("可联网", "可联网")))
        assertEquals("可联网", approvalRiskSummary("可联网", "脚本", listOf("可联网")))
        assertEquals("此操作会修改文件。", approvalRiskSummary("此操作会修改文件。", "文件写入", emptyList()))
    }

    @Test
    fun `请求原因保留普通内容并过滤执行模式参数与风险语句`() {
        assertEquals("Update workspace", approvalRequestReason("Execution mode: semi\nUpdate workspace\nArguments: {}"))
        assertEquals("核对目标", approvalRequestReason("核对目标\n此操作会删除文件且不可恢复。"))
        assertEquals("This can delete files irreversibly.", approvalRiskSummary("This can delete files irreversibly.", "Delete", emptyList()))
    }
}
