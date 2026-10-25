package com.promenar.nexara.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.tool.CommandToolResult
import com.promenar.nexara.ui.linux.output.CommandOutputParser
import com.promenar.nexara.ui.linux.output.CommandRuntime
import com.promenar.nexara.ui.linux.output.CommandTruncation
import com.promenar.nexara.ui.linux.output.formatBytes
import com.promenar.nexara.ui.linux.output.formatSeconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class CommandOutputParserTest {
    private val shellArgs = """{"command":"pip install -r requirements.txt\necho done"}"""

    @Test
    fun `成功结果读取完整 stdout 与 stderr，不判为截断`() {
        val result = CommandToolResult(exitCode = 0, stdout = "hello\n", stderr = "warn\n").toJson().toString()

        val output = CommandOutputParser.parse("shell_exec", shellArgs, result, durationMs = 1_200)

        assertThat(output.exitCode).isEqualTo(0)
        assertThat(output.stdout.raw).isEqualTo("hello\n")
        assertThat(output.stderr.raw).isEqualTo("warn\n")
        assertThat(output.truncated).isFalse()
        assertThat(output.durationMs).isEqualTo(1_200)
        assertThat(output.runtime).isEqualTo(CommandRuntime.SYSTEM)
        assertThat(output.commandLine).isEqualTo("pip install -r requirements.txt …")
        assertThat(output.rerunCommand).isEqualTo("pip install -r requirements.txt\necho done")
        assertThat(output.combinedOnly).isFalse()
    }

    @Test
    fun `执行器尾部截断按原始长度判定`() {
        val result = CommandToolResult(
            exitCode = 0,
            stdout = "tail",
            stdoutOriginalLength = 40_000,
            stdoutTruncated = true,
        ).toJson().toString()

        val output = CommandOutputParser.parse("shell_exec", shellArgs, result, null)

        assertThat(output.stdout.truncation).isEqualTo(CommandTruncation.TAIL_LIMIT)
        assertThat(output.stdout.originalLength).isEqualTo(40_000)
        assertThat(output.stderr.truncated).isFalse()
    }

    @Test
    fun `失败摘要去掉前缀与截断标记并记录原始长度`() {
        val summary = CommandOutputParser.FAILURE_PREFIX +
            """{"exit_code":1,"stdout_original_length":120,"stderr_original_length":9000,"stderr":"…(已截断，原始 9000 字符)ERROR: x","stdout":"short"}"""

        val output = CommandOutputParser.parse("shell_exec", shellArgs, summary, null)

        assertThat(output.exitCode).isEqualTo(1)
        assertThat(output.stderr.raw).isEqualTo("ERROR: x")
        assertThat(output.stderr.truncation).isEqualTo(CommandTruncation.FAILURE_SUMMARY)
        assertThat(output.stderr.originalLength).isEqualTo(9_000)
        // stdout 原始 120 字符只剩 5 字符：摘要压缩过但没有标记时仍判为截断。
        assertThat(output.stdout.truncated).isTrue()
    }

    @Test
    fun `runtime 字段决定运行环境，缺省按工具名推断`() {
        val linux = CommandToolResult(exitCode = 0, backendFields = JsonObject(mapOf("runtime" to JsonPrimitive("linux")))).toJson().toString()

        assertThat(CommandOutputParser.parse("shell_exec", shellArgs, linux, null).runtime).isEqualTo(CommandRuntime.LINUX)
        assertThat(CommandOutputParser.runtimeOf("termux_exec", null)).isEqualTo(CommandRuntime.TERMUX)
        assertThat(CommandOutputParser.runtimeOf("ssh_exec", null)).isEqualTo(CommandRuntime.SSH)
        assertThat(CommandOutputParser.runtimeOf("python_exec", null)).isEqualTo(CommandRuntime.PYTHON)
        assertThat(CommandOutputParser.runtimeOf("job_status", null)).isEqualTo(CommandRuntime.UNKNOWN)
    }

    @Test
    fun `只有 shell_exec 可以在终端重跑`() {
        val result = CommandToolResult(exitCode = 0).toJson().toString()

        assertThat(CommandOutputParser.parse("python_exec", """{"code":"print(1)"}""", result, null).rerunCommand).isNull()
        assertThat(CommandOutputParser.parse("ssh_exec", """{"command":"ls"}""", result, null).rerunCommand).isNull()
        assertThat(CommandOutputParser.parse("python_exec", """{"code":"print(1)"}""", result, null).commandLine).isEqualTo("print(1)")
    }

    @Test
    fun `后台任务状态只有合并日志，日志截断时原始长度未知`() {
        val result = """{"job_id":"j1","state":"exited","exit_code":2,"summary":"make all","elapsed_seconds":75,"log_path":"jobs/j1.log","log_tail":"[31mfail[0m","log_truncated":true}"""

        val output = CommandOutputParser.parse("job_wait", """{"job_id":"j1"}""", result, durationMs = null)

        assertThat(output.combinedOnly).isTrue()
        assertThat(output.commandLine).isEqualTo("make all")
        assertThat(output.exitCode).isEqualTo(2)
        assertThat(output.durationMs).isEqualTo(75_000)
        assertThat(output.stdout.truncation).isEqualTo(CommandTruncation.LOG_TAIL)
        assertThat(output.stdout.originalLength).isNull()
        assertThat(output.logPath).isEqualTo("jobs/j1.log")
    }

    @Test
    fun `非 JSON 结果整体作为 stdout`() {
        val output = CommandOutputParser.parse("shell_exec", null, "plain text", null)

        assertThat(output.stdout.raw).isEqualTo("plain text")
        assertThat(output.exitCode).isNull()
        assertThat(output.commandLine).isNull()
    }

    @Test
    fun `超时与错误码`() {
        val result = CommandToolResult(errorCode = "EXEC_TIMEOUT", timedOut = true).toJson().toString()

        val output = CommandOutputParser.parse("shell_exec", shellArgs, CommandOutputParser.FAILURE_PREFIX + result, null)

        assertThat(output.timedOut).isTrue()
        assertThat(output.errorCode).isEqualTo("EXEC_TIMEOUT")
    }

    @Test
    fun `字节数按 UTF-8 计算并紧凑格式化`() {
        val result = CommandToolResult(exitCode = 0, stdout = "中a").toJson().toString()

        assertThat(CommandOutputParser.parse("shell_exec", shellArgs, result, null).stdout.retainedBytes).isEqualTo(4)
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(3_072)).isEqualTo("3 KB")
        assertThat(formatBytes(49_664)).isEqualTo("48.5 KB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5 MB")
        assertThat(formatSeconds(12_400)).isEqualTo("12.4")
    }
}
