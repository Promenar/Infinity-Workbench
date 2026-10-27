package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.domain.ask.AskUserOption
import com.promenar.nexara.domain.ask.AskUserQuestion
import com.promenar.nexara.domain.ask.AskUserRequest
import org.junit.Test

class AskUserCardLogicTest {
    private val single = AskUserQuestion("框架", "用哪个测试框架？", options = listOf(AskUserOption("JUnit 5（推荐）"), AskUserOption("JUnit 4"), AskUserOption("跟随现有")))
    private val multi = AskUserQuestion("范围", "分支可以做哪些事？", multiSelect = true, options = listOf(AskUserOption("写失败用例"), AskUserOption("加临时桩"), AskUserOption("回报后等待")))
    private val third = AskUserQuestion("命名", "测试类怎么命名？", options = listOf(AskUserOption("FooTest"), AskUserOption("FooSpec")))
    private val request = AskUserRequest(listOf(single, multi, third))

    @Test fun `单选替换，多选切换`() {
        assertThat(askUserToggle(single, setOf(0), 2)).containsExactly(2)
        assertThat(askUserToggle(multi, setOf(0), 2)).containsExactly(0, 2)
        assertThat(askUserToggle(multi, setOf(0, 2), 0)).containsExactly(2)
    }

    @Test fun `每题至少选一项才算答完`() {
        assertThat(askUserIsComplete(request, listOf(setOf(0), emptySet(), setOf(1)))).isFalse()
        assertThat(askUserIsComplete(request, listOf(setOf(0), setOf(1), setOf(1)))).isTrue()
        assertThat(askUserIsComplete(request, listOf(setOf(0)))).isFalse()
    }

    @Test fun `下一道未答题先找后面再回头，都答完为空`() {
        assertThat(askUserNextUnanswered(request, listOf(setOf(0), emptySet(), emptySet()), 0)).isEqualTo(1)
        assertThat(askUserNextUnanswered(request, listOf(emptySet(), setOf(1), setOf(0)), 2)).isEqualTo(0)
        assertThat(askUserNextUnanswered(request, listOf(setOf(0), setOf(1), setOf(0)), 0)).isNull()
    }

    @Test fun `回答按 header 归档，选项按原顺序列出`() {
        val answer = askUserAnswerOf(request, listOf(setOf(0), setOf(2, 0), setOf(1)))
        assertThat(answer.answers).containsExactly(
            "框架", listOf("JUnit 5（推荐）"),
            "范围", listOf("写失败用例", "回报后等待"),
            "命名", listOf("FooSpec"),
        )
        assertThat(answer.other).isNull()
        assertThat(answer.skipped).isFalse()
    }

    private val args = """{"questions":[{"header":"框架","question":"用哪个？","options":[{"label":"A"},{"label":"B"}]}]}"""
    private fun call(id: String = "c1") = ExecutionStep(id = id, type = "tool_call", toolName = "ask_user", toolArgs = args, toolCallId = "call-1")
    private fun result(content: String, type: String = "tool_result") =
        ExecutionStep(id = "res_c1", type = type, toolName = "ask_user", toolCallId = "call-1", content = content)

    @Test fun `已答提问还原为摘要卡并从工具行隐藏`() {
        val steps = listOf(call(), result("""{"answers":{"框架":["B"]}}"""))
        val parsed = askUserSteps(steps)
        assertThat(parsed.hidden).containsExactly("c1", "res_c1")
        assertThat(parsed.answered.single().answer.answers).containsExactly("框架", listOf("B"))
        assertThat(parsed.answered.single().request.questions.single().header).isEqualTo("框架")
    }

    @Test fun `跳过与自由回答同样还原`() {
        assertThat(askUserSteps(listOf(call(), result("""{"skipped":true}"""))).answered.single().answer.skipped).isTrue()
        assertThat(askUserSteps(listOf(call(), result("""{"other":"都不要"}"""))).answered.single().answer.other).isEqualTo("都不要")
    }

    @Test fun `待回答的提问隐藏工具行但不出摘要`() {
        val parsed = askUserSteps(listOf(call()))
        assertThat(parsed.hidden).containsExactly("c1")
        assertThat(parsed.answered).isEmpty()
    }

    @Test fun `失败或无法解析的提问保留为工具行`() {
        assertThat(askUserSteps(listOf(call(), result("参数不合法", type = "error"))).hidden).isEmpty()
        assertThat(askUserSteps(listOf(call(), result("not json"))).hidden).isEmpty()
    }

    @Test fun `其它工具不受影响`() {
        val other = ExecutionStep(id = "x", type = "tool_call", toolName = "file_read", toolArgs = "{}", toolCallId = "call-x")
        assertThat(askUserSteps(listOf(other)).hidden).isEmpty()
    }

    @Test fun `提问调用与随后的回答消息补成执行步骤，再换成摘要卡`() {
        val args = """{"questions":[{"header":"框架","question":"用哪个？","options":[{"label":"甲"},{"label":"乙"}]}]}"""
        val assistant = Message(id = "a1", role = MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(ToolCall("call-1", "ask_user", args), ToolCall("call-2", "ask_user", args)))
        val answered = Message(id = "r1", role = MessageRole.TOOL, content = """{"answers":{"框架":["甲"]}}""", toolCallId = "call-1", name = "ask_user")
        val failed = Message(id = "r2", role = MessageRole.TOOL, content = """{"status":"error","error":"ASK_USER_MULTIPLE_CALLS","message":"一次只能提一个问题。"}""", toolCallId = "call-2", name = "ask_user")

        val steps = askUserSyntheticSteps(assistant, listOf(answered, failed))
        assertThat(steps.map { it.type }).containsExactly("tool_call", "tool_result", "tool_call", "error")
        val asks = askUserSteps(steps)
        assertThat(asks.answered.single().answer.answers).containsExactly("框架", listOf("甲"))
        // 失败的那次留作工具行展示错误。
        assertThat(asks.hidden).containsExactly("ask-call-1", "ask-call-1-result")

        // 尚未回答：只有调用步骤，且被隐藏（由提问卡承担）。
        val pending = askUserSyntheticSteps(assistant.copy(toolCalls = assistant.toolCalls!!.take(1)), emptyList())
        assertThat(askUserSteps(pending).hidden).containsExactly("ask-call-1")

        // 已有执行步骤记录的调用不重复补。
        val recorded = assistant.copy(executionSteps = listOf(ExecutionStep("s", "tool_call", toolCallId = "call-1")))
        assertThat(askUserSyntheticSteps(recorded, listOf(answered)).map { it.id }).containsExactly("ask-call-2")
    }
}
