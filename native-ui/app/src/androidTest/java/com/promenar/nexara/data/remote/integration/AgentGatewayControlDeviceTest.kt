package com.promenar.nexara.data.remote.integration

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.domain.generation.GenerationPhase
import org.junit.After
import org.junit.Test

/** 审批与工具预算收尾的真实模型路径；使用单一稳定模型以限制请求次数。 */
class AgentGatewayControlDeviceTest {
    private val model = AgentGatewayEndToEndDeviceTest.gatewayModels().first()
    private val harness = AgentGatewayHarness(model)
    private var restoreLoopLimit: (() -> Unit)? = null

    @After
    fun tearDown() {
        restoreLoopLimit?.invoke()
        harness.tearDown()
    }

    @Test
    fun semiModeWriteWaitsForApprovalThenExecutes() {
        harness.setUp(executionMode = "semi")

        val first = harness.sendAndAwait(
            "Create the file /approval.md whose entire content is approved-content. " +
                "Call the tool directly without asking me first.",
        )
        assertWithMessage("未进入审批\n${first.describe()}").that(first.pendingApproval).isTrue()
        assertThat(harness.readWorkspaceFile("/approval.md")).isNull()

        val resumed = harness.approvePending()

        assertWithMessage("审批后未成功结束\n${resumed.describe()}")
            .that(resumed.terminalPhase).isEqualTo(GenerationPhase.COMPLETED)
        assertWithMessage("审批后文件内容不符\n${resumed.describe()}")
            .that(harness.readWorkspaceFile("/approval.md")?.trim()).isEqualTo("approved-content")
    }

    @Test
    fun exhaustedToolBudgetEndsWithSummaryInsteadOfFailure() {
        val settings = harness.app.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
        val previous = if (settings.contains("loop_limit")) settings.getInt("loop_limit", 50) else null
        settings.edit().putInt("loop_limit", 1).commit()
        restoreLoopLimit = {
            settings.edit().apply {
                if (previous == null) remove("loop_limit") else putInt("loop_limit", previous)
            }.commit()
        }
        harness.setUp(executionMode = "auto")

        val turn = harness.sendAndAwait(
            "Work strictly one tool call per turn. First call list_files. " +
                "Only after you have seen its result, call search_files with query zzz. " +
                "Only after that, call list_files again. Then summarize what you found.",
        )

        assertWithMessage("预算耗尽不应导致失败\n${turn.describe()}")
            .that(turn.terminalPhase).isEqualTo(GenerationPhase.COMPLETED)
        assertWithMessage("缺少预算拒绝结果\n${turn.describe()}")
            .that(turn.toolMessages.any { it.content.contains("工具预算上限") }).isTrue()
        assertWithMessage("缺少总结回答\n${turn.describe()}")
            .that(turn.finalAnswer.isNotBlank()).isTrue()
    }
}
