package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.Test

class ToolExecutionPolicyTest {
    @Test
    fun `semi 只允许已知安全只读与会话内部状态工具免审批`() {
        val exempt = setOf(ToolRisk.SAFE_READ, ToolRisk.SESSION_STATE)
        exempt.forEach { risk ->
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.SEMI, risk)).isFalse()
        }

        ToolRisk.entries.filterNot { it in exempt }.forEach { risk ->
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.SEMI, risk)).isTrue()
        }
    }

    @Test
    fun `manual 全部审批而 auto 的定时执行仍需审批`() {
        ToolRisk.entries.forEach { risk ->
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.MANUAL, risk)).isTrue()
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.AUTO, risk)).isEqualTo(risk == ToolRisk.SCHEDULE)
        }
    }
}
