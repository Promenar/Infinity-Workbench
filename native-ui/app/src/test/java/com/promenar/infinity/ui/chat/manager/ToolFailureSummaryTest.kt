package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.termux.TermuxCommandResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class ToolFailureSummaryTest {
    private fun body(summary: String): JsonObject {
        assertThat(summary).startsWith(ToolFailureSummary.PREFIX)
        return Json.parseToJsonElement(summary.removePrefix(ToolFailureSummary.PREFIX)).jsonObject
    }

    @Test
    fun `long stdout keeps exit code and stderr tail of a termux failure`() {
        val stdout = "line\n".repeat(4_000)
        val content = TermuxCommandResult(stdout = stdout, stderr = "fatal: not a git repository", exitCode = 128).toJson().toString()

        val summary = ToolFailureSummary.format(content)

        assertThat(summary.length).isAtMost(ToolFailureSummary.PREFIX.length + ToolFailureSummary.MAX_CHARS)
        val json = body(summary)
        assertThat(json["exit_code"]!!.jsonPrimitive.content).isEqualTo("128")
        assertThat(json["stderr"]!!.jsonPrimitive.content).isEqualTo("fatal: not a git repository")
        val keptStdout = json["stdout"]!!.jsonPrimitive.content
        assertThat(keptStdout).startsWith("…(已截断，原始 20000 字符)")
        assertThat(keptStdout).endsWith("line\n")
    }

    @Test
    fun `root cause fields come first and null fields are omitted`() {
        val content = TermuxCommandResult(errorCode = "TERMUX_NOT_INSTALLED", guidance = "请先安装 Termux。").toJson().toString()

        val json = body(ToolFailureSummary.format(content))

        assertThat(json.keys.take(2)).containsExactly("error_code", "guidance").inOrder()
        assertThat(json["error_code"]!!.jsonPrimitive.content).isEqualTo("TERMUX_NOT_INSTALLED")
        assertThat(json["guidance"]!!.jsonPrimitive.content).isEqualTo("请先安装 Termux。")
        assertThat(json).doesNotContainKey("exit_code")
        assertThat(json).doesNotContainKey("errmsg")
    }

    @Test
    fun `stderr is compressed after stdout and guidance is never truncated`() {
        val guidance = "指引".repeat(1_000)
        val content = buildJsonObject {
            put("error_code", "X")
            put("guidance", guidance)
            put("stdout", "o".repeat(10_000))
            put("stderr", "e".repeat(10_000))
        }.toString()

        val json = body(ToolFailureSummary.format(content))

        assertThat(json["guidance"]!!.jsonPrimitive.content).isEqualTo(guidance)
        assertThat(json["stdout"]!!.jsonPrimitive.content).isEqualTo("…(已截断，原始 10000 字符)")
        assertThat(json["stderr"]!!.jsonPrimitive.content).startsWith("…(已截断，原始 10000 字符)e")
    }

    @Test
    fun `reported original length beats the already truncated text length`() {
        val content = TermuxCommandResult(stdout = "x".repeat(6_000), stdoutOriginalLength = 120_000, exitCode = 1).toJson().toString()

        val json = body(ToolFailureSummary.format(content))

        assertThat(json["stdout"]!!.jsonPrimitive.content).startsWith("…(已截断，原始 120000 字符)")
    }

    @Test
    fun `ssh exec style result keeps exit code`() {
        val content = buildJsonObject {
            put("exit_code", 2)
            put("stdout", "a".repeat(9_000))
            put("stderr", "ls: cannot access")
            put("truncated", false)
            put("timed_out", false)
        }.toString()

        val json = body(ToolFailureSummary.format(content))

        assertThat(json["exit_code"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(json["stderr"]!!.jsonPrimitive.content).isEqualTo("ls: cannot access")
        assertThat(json["truncated"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `plain text failure keeps short messages and truncates long ones with original length`() {
        assertThat(ToolFailureSummary.format("未找到条目：/a.txt")).isEqualTo("工具执行失败：未找到条目：/a.txt")

        val long = "错".repeat(1_500)
        val summary = ToolFailureSummary.format(long)

        assertThat(summary).isEqualTo("工具执行失败：" + "错".repeat(1_000) + "…(已截断，原始 1500 字符)")
    }

    @Test
    fun `json without recognised fields is treated as plain text`() {
        val content = buildJsonObject { put("message", "m".repeat(2_000)) }.toString()

        val summary = ToolFailureSummary.format(content)

        assertThat(summary).startsWith("工具执行失败：{\"message\":\"mmm")
        assertThat(summary).endsWith("…(已截断，原始 ${content.length} 字符)")
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "😀"
        val plain = ToolFailureSummary.format("a" + emoji.repeat(1_000))
        assertThat(plain.removePrefix(ToolFailureSummary.PREFIX).substringBefore("…").last().isHighSurrogate()).isFalse()

        val content = buildJsonObject { put("exit_code", 1); put("stdout", "b" + emoji.repeat(5_000)) }.toString()
        val kept = body(ToolFailureSummary.format(content))["stdout"]!!.jsonPrimitive.content.substringAfter("字符)")
        assertThat(kept.first().isLowSurrogate()).isFalse()
    }
}
