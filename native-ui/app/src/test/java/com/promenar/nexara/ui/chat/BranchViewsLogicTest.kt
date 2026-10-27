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

    @Test fun `后端带外层包装的方案结果也能还原`() {
        val plan = BranchPlan("补测试", "g", approvalMode = "workspace")
        val answer = com.promenar.nexara.domain.branch.BranchPlanAnswer(BranchDecision.DISPATCH, BranchWorkMode.SHARED, BranchContextMode.FRESH, "workspace", "s-b")
        val content = com.promenar.nexara.data.branch.BranchPlanProtocol.result(plan, answer).toString()
        val resolved = branchPlanSteps(listOf(call(), result(content))).resolved.single()
        assertThat(resolved.answer).isEqualTo(answer)
        assertThat(branchPlanTitleOf(com.promenar.nexara.data.branch.BranchPlanProtocol.encode(plan))).isEqualTo("补测试")
    }

    @Test fun `成功的 session_send 工具行隐藏，失败与进行中的保留`() {
        fun send(id: String) = ExecutionStep(id = "$id-c", type = "tool_call", toolName = "session_send", toolArgs = "{}", toolCallId = id)
        fun done(id: String, type: String = "tool_result") = ExecutionStep(id = "$id-r", type = type, toolName = "session_send", toolCallId = id, content = "{}")
        val hidden = sessionSendHiddenSteps(listOf(send("ok"), done("ok"), send("bad"), done("bad", "error"), send("running")))
        assertThat(hidden).containsExactly("ok-c", "ok-r")
    }

    @Test fun `发送副本留在本轮内按位置成为记录步骤，内部回执不显示`() {
        val sendCall = com.promenar.nexara.data.model.ToolCall("call-s", "session_send", "{}")
        val messages = listOf(
            com.promenar.nexara.data.model.Message("u1", com.promenar.nexara.data.model.MessageRole.USER, "派去做"),
            com.promenar.nexara.data.model.Message("a1", com.promenar.nexara.data.model.MessageRole.ASSISTANT, "", toolCalls = listOf(sendCall),
                executionSteps = listOf(ExecutionStep("a1-c", "tool_call", toolName = "session_send", toolArgs = "{}", toolCallId = "call-s"))),
            com.promenar.nexara.data.model.Message("mail", com.promenar.nexara.data.model.MessageRole.SYSTEM, "{}",
                name = com.promenar.nexara.data.branch.SessionMessageStore.SENT_MESSAGE_NAME),
            com.promenar.nexara.data.model.Message("t1", com.promenar.nexara.data.model.MessageRole.TOOL, "ok", toolCallId = "call-s", name = "session_send"),
            com.promenar.nexara.data.model.Message("ack", com.promenar.nexara.data.model.MessageRole.SYSTEM, "{}",
                name = com.promenar.nexara.data.branch.SessionMessageStore.DELIVERY_MESSAGE_NAME),
            com.promenar.nexara.data.model.Message("a2", com.promenar.nexara.data.model.MessageRole.ASSISTANT, "已发出"),
        )
        val groups = buildPipelineGroups(messages)
        assertThat(groups.map { group -> group.messages.map { it.id } }).containsExactly(listOf("u1"), listOf("a1", "mail", "t1", "a2")).inOrder()
        val steps = buildPipelineRenderPlan(groups[1].messages, isGenerating = false, hasStreamingContent = false).steps
        assertThat(steps.map { it::class.simpleName }).containsExactly("ToolExec", "SessionRecord", "Content").inOrder()
    }
}
