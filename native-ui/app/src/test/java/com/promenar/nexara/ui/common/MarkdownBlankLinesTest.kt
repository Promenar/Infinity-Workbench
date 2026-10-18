package com.promenar.nexara.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlankLinesTest {
    @Test
    fun `普通段落连续三个以上换行折叠为两个`() {
        assertEquals("第一段\n\n第二段\n\n第三段", collapseMarkdownBlankLines("第一段\n\n\n第二段\n\n\n\n第三段"))
    }

    @Test
    fun `两个换行与没有换行的内容保持原文`() {
        for (text in listOf("一\n\n二", "### 标题正文", "一\n二", "")) {
            assertEquals(text, collapseMarkdownBlankLines(text))
        }
    }

    @Test
    fun `反引号波浪号和未闭合围栏里的多空行均保留`() {
        for (fence in listOf("```", "~~~~")) {
            val input = "开头\n\n\n${fence}text\n一\n\n\n\n二\n$fence\n\n\n末尾"
            val expected = "开头\n\n${fence}text\n一\n\n\n\n二\n$fence\n\n末尾"
            assertEquals(expected, collapseMarkdownBlankLines(input))
        }
        val unfinished = "```text\n一\n\n\n二"
        assertEquals(unfinished, collapseMarkdownBlankLines(unfinished))
    }

    @Test
    fun `缩进代码内连续空行保持原文`() {
        for (indent in listOf("    ", "\t", "  \t")) {
            val code = "${indent}val a = 1\n\n\n\n${indent}val b = 2"
            assertEquals(code, collapseMarkdownBlankLines(code))
        }
    }

    @Test
    fun `行内代码的内容与多反引号边界保持原文`() {
        val code = "保留 ``代码 `内容`\n\n\n下一行`` 和 `x`。"
        assertEquals(code, collapseMarkdownBlankLines(code))
    }

    @Test
    fun `链接文字目标与引用定义不改变`() {
        val links = listOf(
            "[第一行\n\n\n第二行](https://example.com/a_(b))",
            "[链接](https://example.com/\n\n\npath)",
            "[带[嵌套]的\n\n\n标题](https://example.com)",
            "[手册]: <https://example.com> \"标题\"",
        )
        links.forEach { assertEquals(it, collapseMarkdownBlankLines(it)) }
    }

    @Test
    fun `表格行和表格内容保持原文`() {
        val table = "| 列一 | 列二 |\n|---|---|\n| `a` | [b](https://example.com) |\n| c | d |"
        assertEquals(table, collapseMarkdownBlankLines(table))
    }

    @Test
    fun `CRLF按原换行形式折叠并保护围栏代码`() {
        val input = "前文\r\n\r\n\r\n后文\r\n\r\n```\r\n一\r\n\r\n\r\n二\r\n```"
        val expected = "前文\r\n\r\n后文\r\n\r\n```\r\n一\r\n\r\n\r\n二\r\n```"
        assertEquals(expected, collapseMarkdownBlankLines(input))
    }
}
