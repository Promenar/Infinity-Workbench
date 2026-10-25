package com.promenar.nexara.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.linux.output.AnsiDocument
import com.promenar.nexara.ui.linux.output.AnsiParser
import com.promenar.nexara.ui.linux.output.CommandOutput
import com.promenar.nexara.ui.linux.output.CommandRuntime
import com.promenar.nexara.ui.linux.output.CommandStream
import com.promenar.nexara.ui.linux.output.OutputDocument
import com.promenar.nexara.ui.linux.output.OutputSearch
import com.promenar.nexara.ui.linux.output.OutputSegment
import com.promenar.nexara.ui.linux.output.OutputSegments
import com.promenar.nexara.ui.linux.output.splitLines
import org.junit.Test

class OutputDocumentTest {
    private val separator = "── stderr ──"

    private fun output(stdout: String, stderr: String) = CommandOutput(
        toolName = "shell_exec",
        commandLine = "x",
        rerunCommand = "x",
        exitCode = 0,
        timedOut = false,
        errorCode = null,
        durationMs = null,
        runtime = CommandRuntime.LINUX,
        stdout = CommandStream(stdout, stdout.length.toLong(), null),
        stderr = CommandStream(stderr, stderr.length.toLong(), null),
        combinedOnly = false,
    )

    @Test
    fun `合并视图依次为 stdout、分隔行与 stderr，并标出各段范围`() {
        val document = OutputSegments.build(output("out1\nout2", "\u001B[31merr\u001B[0m\n"), OutputSegment.MERGED, separator)

        assertThat(document.text).isEqualTo("out1\nout2\n$separator\nerr\n")
        assertThat(document.text.substring(document.separatorRange!!)).isEqualTo(separator)
        assertThat(document.text.substring(document.stderrRange!!)).isEqualTo("err\n")
        // stderr 的颜色区间随拼接平移。
        assertThat(document.ansi.spans.single().start).isEqualTo(document.stderrRange!!.first)
        assertThat(document.lines.map { document.text.substring(it) }).containsExactly("out1", "out2", separator, "err").inOrder()
    }

    @Test
    fun `任一路为空时不加分隔行`() {
        val onlyOut = OutputSegments.build(output("a\n", ""), OutputSegment.MERGED, separator)
        val onlyErr = OutputSegments.build(output("", "e"), OutputSegment.MERGED, separator)

        assertThat(onlyOut.text).isEqualTo("a\n")
        assertThat(onlyOut.stderrRange).isNull()
        assertThat(onlyErr.text).isEqualTo("e")
        assertThat(onlyErr.stderrRange).isEqualTo(0..0)
        assertThat(onlyErr.separatorRange).isNull()
    }

    @Test
    fun `单独分段只含对应输出`() {
        val source = output("o", "e")

        assertThat(OutputSegments.build(source, OutputSegment.STDOUT, separator).text).isEqualTo("o")
        assertThat(OutputSegments.build(source, OutputSegment.STDERR, separator).text).isEqualTo("e")
        assertThat(OutputSegments.build(source, OutputSegment.STDERR, separator).stderrRange).isNull()
    }

    @Test
    fun `分行忽略末尾换行，保留中间空行`() {
        assertThat(splitLines("")).isEmpty()
        assertThat(splitLines("a\n\nb\n")).containsExactly(0..0, 2 until 2, 3..3).inOrder()
    }

    @Test
    fun `按偏移查找所在行`() {
        val document = OutputDocument.of(AnsiParser.parse("ab\ncd\nef"))

        assertThat(document.lineOf(0)).isEqualTo(0)
        assertThat(document.lineOf(4)).isEqualTo(1)
        assertThat(document.lineOf(7)).isEqualTo(2)
        assertThat(OutputDocument.of(AnsiDocument.EMPTY).lineOf(3)).isEqualTo(0)
    }

    @Test
    fun `搜索不区分大小写且命中不重叠`() {
        val search = OutputSearch.find("Error aaa error ERROR", "error")

        assertThat(search.matches).containsExactly(0..4, 10..14, 16..20).inOrder()
        assertThat(search.current).isEqualTo(0)
        assertThat(OutputSearch.find("aaaa", "aa").matches).containsExactly(0..1, 2..3).inOrder()
    }

    @Test
    fun `上一个与下一个循环跳转`() {
        val search = OutputSearch.find("x x x", "x")

        assertThat(search.next().current).isEqualTo(1)
        assertThat(search.next().next().next().current).isEqualTo(0)
        assertThat(search.previous().current).isEqualTo(2)
        assertThat(search.previous().currentMatch).isEqualTo(4..4)
    }

    @Test
    fun `改查询时停在原位置之后的第一个命中`() {
        val search = OutputSearch.find("ab ab ab", "ab", keepNear = 3)

        assertThat(search.current).isEqualTo(1)
        assertThat(OutputSearch.find("ab ab", "ab", keepNear = 100).current).isEqualTo(0)
    }

    @Test
    fun `空查询与无命中`() {
        assertThat(OutputSearch.find("abc", "").matches).isEmpty()
        val none = OutputSearch.find("abc", "z")
        assertThat(none.current).isEqualTo(-1)
        assertThat(none.currentMatch).isNull()
        assertThat(none.next()).isEqualTo(none)
    }
}
