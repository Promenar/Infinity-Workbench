package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.Test

class ToolExecutionPolicyTest {
    @Test
    fun `semi 只允许已知安全只读工具免审批`() {
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.SEMI, ToolRisk.SAFE_READ)).isFalse()

        ToolRisk.entries.filterNot { it == ToolRisk.SAFE_READ }.forEach { risk ->
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.SEMI, risk)).isTrue()
        }
    }

    @Test
    fun `manual 全部审批而 auto 只跳过审批`() {
        ToolRisk.entries.forEach { risk ->
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.MANUAL, risk)).isTrue()
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.AUTO, risk)).isFalse()
        }
    }
}
