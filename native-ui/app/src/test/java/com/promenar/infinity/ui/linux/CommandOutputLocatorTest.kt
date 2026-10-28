package com.promenar.infinity.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.ui.linux.output.CommandOutputFiles
import com.promenar.infinity.ui.linux.output.CommandOutputLoad
import com.promenar.infinity.ui.linux.output.CommandOutputLocator
import com.promenar.infinity.ui.linux.output.OutputSegment
import org.junit.Test
import java.time.ZoneOffset

class CommandOutputLocatorTest {
    private fun call(id: String, callId: String, name: String, args: String, at: Long) =
        ExecutionStep(id = id, type = "tool_call", toolName = name, toolArgs = args, toolCallId = callId, timestamp = at)

    private fun result(id: String, callId: String, name: String, content: String, at: Long, type: String = "tool_result") =
        ExecutionStep(id = id, type = type, toolName = name, toolCallId = callId, content = content, timestamp = at)

    @Test
    fun `按工具调用 id 定位结果并计算耗时`() {
        val steps = listOf(
            call("s1", "call_a", "shell_exec", """{"command":"ls"}""", 1_000),
            call("s2", "call_b", "shell_exec", """{"command":"pwd"}""", 1_100),
            result("res_s2", "call_b", "shell_exec", CommandToolResult(exitCode = 0, stdout = "/workspace\n").toJson().toString(), 1_600),
            result("res_s1", "call_a", "shell_exec", CommandToolResult(exitCode = 0, stdout = "a b\n").toJson().toString(), 3_500),
        )

        val load = CommandOutputLocator.fromSteps(steps, "call_b") as CommandOutputLoad.Loaded

        assertThat(load.output.commandLine).isEqualTo("pwd")
        assertThat(load.output.stdout.raw).isEqualTo("/workspace\n")
        assertThat(load.output.durationMs).isEqualTo(500)
    }

    @Test
    fun `失败步骤同样可读`() {
        val steps = listOf(
            call("s1", "c1", "shell_exec", """{"command":"false"}""", 0),
            result("res_s1", "c1", "shell_exec", "工具执行失败：" + CommandToolResult(exitCode = 1, stderr = "boom").toJson(), 10, type = "error"),
        )

        val load = CommandOutputLocator.fromSteps(steps, "c1") as CommandOutputLoad.Loaded

        assertThat(load.output.exitCode).isEqualTo(1)
        assertThat(load.output.stderr.raw).isEqualTo("boom")
    }

    @Test
    fun `没有结果为等待中，非命令工具与找不到为不存在`() {
        val steps = listOf(
            call("s1", "c1", "shell_exec", """{"command":"sleep 9"}""", 0),
            call("s2", "c2", "read_file", """{"path":"/a"}""", 0),
            result("res_s2", "c2", "read_file", "content", 1),
        )

        assertThat(CommandOutputLocator.fromSteps(steps, "c1")).isEqualTo(CommandOutputLoad.Pending)
        assertThat(CommandOutputLocator.fromSteps(steps, "c2")).isEqualTo(CommandOutputLoad.NotFound)
        assertThat(CommandOutputLocator.fromSteps(steps, "missing")).isEqualTo(CommandOutputLoad.NotFound)
    }

    @Test
    fun `保存文件名带时间与分段，重名时加序号`() {
        val name = CommandOutputFiles.fileName(OutputSegment.STDERR, 1_761_400_212_000, ZoneOffset.UTC)

        assertThat(name).isEqualTo("command-20251025-135012-stderr.txt")
        assertThat(CommandOutputFiles.fileName(OutputSegment.MERGED, 0, ZoneOffset.UTC)).endsWith("-output.txt")
        assertThat(CommandOutputFiles.numbered(name, 1)).isEqualTo(name)
        assertThat(CommandOutputFiles.numbered(name, 3)).isEqualTo("command-20251025-135012-stderr-3.txt")
        assertThat(CommandOutputFiles.numbered("noext", 2)).isEqualTo("noext-2")
    }
}
