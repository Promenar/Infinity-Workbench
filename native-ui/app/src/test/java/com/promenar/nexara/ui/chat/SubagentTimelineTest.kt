package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.subagent.SubagentEvent
import com.promenar.nexara.data.subagent.SubagentEvent.Kind
import org.junit.Test

class SubagentTimelineTest {
    private fun event(kind: Kind, text: String = "", tool: String? = null, failed: Boolean = false) =
        SubagentEvent(kind, text = text, toolName = tool, failed = failed, at = 0)

    @Test
    fun `调用、审批与结果按顺序归入同一步，说明文字单独成段`() {
        val items = subagentTimeline(
            listOf(
                event(Kind.TEXT, "先列出目录。"),
                event(Kind.TOOL_CALL, """{"path":"data/remote"}""", "list_files"),
                event(Kind.TOOL_RESULT, "provider/", "list_files"),
                event(Kind.TOOL_CALL, """{"command":"./gradlew test"}""", "shell_exec"),
                event(Kind.APPROVAL, "./gradlew test", "shell_exec"),
                event(Kind.TOOL_RESULT, "用户拒绝", "shell_exec", failed = true),
            ),
            runError = null,
        )

        assertThat(items).hasSize(3)
        assertThat(items[0]).isEqualTo(SubagentTimelineItem.Narration("先列出目录。"))
        val list = items[1] as SubagentTimelineItem.Tool
        assertThat(list.call).isEqualTo(SubagentCallDisplay("data/remote", null))
        assertThat(list.result?.text).isEqualTo("provider/")
        val shell = items[2] as SubagentTimelineItem.Tool
        assertThat(shell.call.subject).isEqualTo("./gradlew test")
        assertThat(shell.approval).isNotNull()
        assertThat(shell.result?.failed).isTrue()
    }

    @Test
    fun `与交回结果同文的终止错误不在时间线重复，正在等待的调用标为失败`() {
        val timeout = "审批在 10 分钟内未处理，子代理已停止。"
        val items = subagentTimeline(
            listOf(
                event(Kind.TOOL_CALL, """{"command":"ls"}""", "shell_exec"),
                event(Kind.APPROVAL, "ls", "shell_exec"),
                event(Kind.ERROR, timeout, failed = true),
            ),
            runError = timeout,
        )

        assertThat(items).hasSize(1)
        val step = items.single() as SubagentTimelineItem.Tool
        assertThat(step.result?.failed).isTrue()
        assertThat(step.result?.text).isEmpty()
    }

    @Test
    fun `中途的独立错误保留为失败条目`() {
        val items = subagentTimeline(
            listOf(event(Kind.ERROR, "模型返回为空", failed = true), event(Kind.TEXT, "重试。")),
            runError = null,
        )
        assertThat(items).containsExactly(
            SubagentTimelineItem.Failure("模型返回为空"),
            SubagentTimelineItem.Narration("重试。"),
        ).inOrder()
    }

    @Test
    fun `参数取最能说明对象的一项，其余格式化为补充`() {
        assertThat(subagentCallDisplay("""{"path":"a.kt"}""")).isEqualTo(SubagentCallDisplay("a.kt", null))
        assertThat(subagentCallDisplay("{}")).isEqualTo(SubagentCallDisplay(null, null))
        assertThat(subagentCallDisplay("not json")).isEqualTo(SubagentCallDisplay(null, "not json"))

        val display = subagentCallDisplay("""{"query":"stream","limit":5,"mode":"fast","tags":["a"]}""")
        assertThat(display.subject).isEqualTo("stream")
        assertThat(display.details).isEqualTo("limit: 5\nmode: fast\ntags: [\"a\"]")

        // 没有可作对象的字符串参数时逐项展示。
        assertThat(subagentCallDisplay("""{"limit":5}""")).isEqualTo(SubagentCallDisplay(null, "limit: 5"))
    }
}
