package com.promenar.nexara.data.branch

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.branch.*
import org.junit.Test

class BranchPlanProtocolTest {
    private val valid = """{"title":"验证账单","goal":"测试账单并通过验收","steps":["检查输入","运行测试"],"exclusions":"不部署","communication":"报告阻断"}"""

    @Test fun `省略模式取主会话快照且默认共享与全新上下文`() {
        val plan = BranchPlanProtocol.decode(valid, "full_access").getOrThrow()
        assertThat(plan.approvalMode).isEqualTo("full_access")
        assertThat(plan.workMode).isEqualTo(BranchWorkMode.SHARED)
        assertThat(plan.contextMode).isEqualTo(BranchContextMode.FRESH)
    }
    @Test fun `空目标过长主题空步骤非法枚举和未知字段被拒绝`() {
        listOf(valid.replace("验证账单", "x".repeat(61)), valid.replace("测试账单并通过验收", " "),
            valid.replace("[\"检查输入\",\"运行测试\"]", "[]"), valid.dropLast(1) + ",\"approvalMode\":\"admin\"}",
            valid.dropLast(1) + ",\"workMode\":\"invalid\"}", valid.dropLast(1) + ",\"extra\":true}")
            .forEach { assertThat(BranchPlanProtocol.decode(it, "workspace").isFailure).isTrue() }
    }
    @Test fun `没有worktree细节的答案不能切换工作方式`() {
        val plan = BranchPlanProtocol.decode(valid, "workspace").getOrThrow()
        assertThat(BranchPlanProtocol.validateAnswer(plan,
            BranchPlanAnswer(BranchDecision.DISPATCH, BranchWorkMode.WORKTREE, BranchContextMode.INHERIT, "full_access"))).isNotNull()
    }
}
