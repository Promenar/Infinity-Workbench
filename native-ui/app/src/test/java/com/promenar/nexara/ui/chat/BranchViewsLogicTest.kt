package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.domain.branch.BranchContextMode
import com.promenar.nexara.domain.branch.BranchDecision
import com.promenar.nexara.domain.branch.BranchPlan
import com.promenar.nexara.domain.branch.BranchWorkMode
import com.promenar.nexara.domain.branch.BranchWorktree
import com.promenar.nexara.domain.branch.PendingBranchPlan
import com.promenar.nexara.domain.model.ExecutionMode
import org.junit.Test

class BranchViewsLogicTest {
    private val worktree = BranchWorktree("feat/x", "main", "../x")

    private fun pending(plan: BranchPlan, parent: String = "full_access") = PendingBranchPlan("s", "m", "c", plan, parent)

    @Test fun `初始选择取方案取值，审批模式缺省沿用主会话`() {
        val plan = BranchPlan("t", "g", workMode = BranchWorkMode.WORKTREE, worktree = worktree, contextMode = BranchContextMode.INHERIT, approvalMode = "")
        val choice = branchPlanInitialChoice(pending(plan))
        assertThat(choice.workMode).isEqualTo(BranchWorkMode.WORKTREE)
        assertThat(choice.contextMode).isEqualTo(BranchContextMode.INHERIT)
        assertThat(choice.approvalMode).isEqualTo(ExecutionMode.FULL_ACCESS)
        assertThat(branchPlanInitialChoice(pending(plan.copy(approvalMode = "workspace"))).approvalMode).isEqualTo(ExecutionMode.WORKSPACE)
    }

    @Test fun `没有 worktree 细节时只能共用工作区且不可切换`() {
        val plan = BranchPlan("t", "g", workMode = BranchWorkMode.WORKTREE, worktree = null, approvalMode = "workspace")
        assertThat(branchPlanInitialChoice(pending(plan)).workMode).isEqualTo(BranchWorkMode.SHARED)
        assertThat(branchPlanCanToggleWorkMode(plan)).isFalse()
        assertThat(branchPlanCanToggleWorkMode(plan.copy(worktree = worktree))).isTrue()
    }

    @Test fun `回答带上用户最终选择的三项`() {
        val answer = branchPlanAnswerOf(BranchDecision.DISPATCH, BranchPlanChoice(BranchWorkMode.SHARED, BranchContextMode.FRESH, ExecutionMode.FULL_ACCESS))
        assertThat(answer.decision).isEqualTo(BranchDecision.DISPATCH)
        assertThat(answer.workMode).isEqualTo(BranchWorkMode.SHARED)
        assertThat(answer.contextMode).isEqualTo(BranchContextMode.FRESH)
        assertThat(answer.approvalMode).isEqualTo("full_access")
        assertThat(answer.branchSessionId).isNull()
    }

    private val args = """{"title":"补测试","goal":"g"}"""
    private fun call() = ExecutionStep(id = "c1", type = "tool_call", toolName = "session_branch", toolArgs = args, toolCallId = "call-1")
    private fun result(content: String, type: String = "tool_result") =
        ExecutionStep(id = "r1", type = type, toolName = "session_branch", toolCallId = "call-1", content = content)

    @Test fun `已派发方案还原为标题与分支 ID，并隐藏工具行`() {
        val steps = listOf(call(), result("""{"decision":"dispatch","workMode":"worktree","contextMode":"inherit","approvalMode":"workspace","branchSessionId":"s-b"}"""))
        val parsed = branchPlanSteps(steps)
        assertThat(parsed.hidden).containsExactly("c1", "r1")
        val resolved = parsed.resolved.single()
        assertThat(resolved.title).isEqualTo("补测试")
        assertThat(resolved.answer.branchSessionId).isEqualTo("s-b")
        assertThat(resolved.answer.workMode).isEqualTo(BranchWorkMode.WORKTREE)
    }

    @Test fun `待处理的方案只隐藏工具行，失败的保留为工具行`() {
        assertThat(branchPlanSteps(listOf(call())).let { it.resolved.isEmpty() && it.hidden == setOf("c1") }).isTrue()
        val failed = branchPlanSteps(listOf(call(), result("""{"status":"error","message":"缺少目标"}""", type = "error")))
        assertThat(failed.resolved).isEmpty()
        assertThat(failed.hidden).isEmpty()
    }

    @Test fun `结果无法解析时回退为工具行`() {
        val parsed = branchPlanSteps(listOf(call(), result("not json")))
        assertThat(parsed.resolved).isEmpty()
        assertThat(parsed.hidden).isEmpty()
    }
}
