package com.promenar.infinity.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.linux.output.AnsiColor
import com.promenar.infinity.ui.linux.output.AnsiDocument
import com.promenar.infinity.ui.linux.output.AnsiParser
import com.promenar.infinity.ui.linux.output.AnsiSpan
import com.promenar.infinity.ui.linux.output.AnsiStyle
import com.promenar.infinity.ui.linux.output.concatAnsi
import org.junit.Test

class AnsiParserTest {
    private val esc = "\u001B"

    @Test
    fun `前景色与重置生成独立样式区间`() {
        val document = AnsiParser.parse("$esc[31merror$esc[0m: done")

        assertThat(document.text).isEqualTo("error: done")
        assertThat(document.spans).containsExactly(AnsiSpan(0, 5, AnsiStyle(foreground = AnsiColor.Indexed(1))))
    }

    @Test
    fun `粗体与高亮色叠加，22 只取消粗体`() {
        val document = AnsiParser.parse("$esc[1;92mOK$esc[22m plain-green$esc[39m end")

        assertThat(document.text).isEqualTo("OK plain-green end")
        assertThat(document.spans).containsExactly(
            AnsiSpan(0, 2, AnsiStyle(foreground = AnsiColor.Indexed(10), bold = true)),
            AnsiSpan(2, 14, AnsiStyle(foreground = AnsiColor.Indexed(10))),
        ).inOrder()
    }

    @Test
    fun `256 色与真彩色`() {
        val indexed = AnsiParser.parse("$esc[38;5;208mx")
        val rgb = AnsiParser.parse("$esc[38;2;10;20;300my$esc[48;5;4mz")

        assertThat(indexed.spans.single().style.foreground).isEqualTo(AnsiColor.Indexed(208))
        assertThat(rgb.spans[0].style.foreground).isEqualTo(AnsiColor.Rgb(10, 20, 255))
        assertThat(rgb.spans[1].style).isEqualTo(AnsiStyle(foreground = AnsiColor.Rgb(10, 20, 255), background = AnsiColor.Indexed(4)))
    }

    @Test
    fun `空参数的 SGR 等同重置`() {
        val document = AnsiParser.parse("$esc[34ma$esc[mb")

        assertThat(document.spans).containsExactly(AnsiSpan(0, 1, AnsiStyle(foreground = AnsiColor.Indexed(4))))
    }

    @Test
    fun `丢弃其它 CSI、OSC 与字符集序列`() {
        val document = AnsiParser.parse("$esc]0;title\u0007$esc[2K$esc[1Ga$esc(Bb$esc]8;;http://x$esc\\c")

        assertThat(document.text).isEqualTo("abc")
        assertThat(document.spans).isEmpty()
    }

    @Test
    fun `单独回车从行首覆盖，CRLF 视为换行`() {
        val document = AnsiParser.parse("first\r\n10%\r$esc[32m100%$esc[0m\nnext")

        assertThat(document.text).isEqualTo("first\n100%\nnext")
        assertThat(document.spans).containsExactly(AnsiSpan(6, 10, AnsiStyle(foreground = AnsiColor.Indexed(2))))
    }

    @Test
    fun `回车覆盖时裁掉被覆盖的样式区间`() {
        val document = AnsiParser.parse("$esc[31mold text$esc[0m\rnew")

        assertThat(document.text).isEqualTo("new")
        assertThat(document.spans).isEmpty()
    }

    @Test
    fun `退格删除前一个字符，其它控制字符丢弃`() {
        val document = AnsiParser.parse("ab\bc\u0000\u0007\td")

        assertThat(document.text).isEqualTo("ac\td")
    }

    @Test
    fun `末尾不完整的序列被忽略`() {
        assertThat(AnsiParser.parse("ok$esc[3").text).isEqualTo("ok")
        assertThat(AnsiParser.parse("ok$esc").text).isEqualTo("ok")
    }

    @Test
    fun `反显与下划线开关`() {
        val document = AnsiParser.parse("$esc[4;7mx$esc[24;27my")

        assertThat(document.spans).containsExactly(AnsiSpan(0, 1, AnsiStyle(underline = true, inverse = true)))
    }

    @Test
    fun `拼接时样式区间整体平移`() {
        val merged = concatAnsi(
            listOf(
                AnsiDocument("ab", listOf(AnsiSpan(0, 1, AnsiStyle(bold = true)))),
                AnsiDocument("cd", listOf(AnsiSpan(1, 2, AnsiStyle(italic = true)))),
            ),
        )

        assertThat(merged.text).isEqualTo("abcd")
        assertThat(merged.spans).containsExactly(AnsiSpan(0, 1, AnsiStyle(bold = true)), AnsiSpan(3, 4, AnsiStyle(italic = true))).inOrder()
    }
}
