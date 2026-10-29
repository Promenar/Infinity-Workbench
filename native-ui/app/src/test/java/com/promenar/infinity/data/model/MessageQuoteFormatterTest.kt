package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageQuoteFormatterTest {
    private fun quote(text: String = "引文", annotation: String = "批注") = MessageQuote("a", text, annotation)

    @Test
    fun `单段引用的批注位于正文之前`() {
        assertThat(MessageQuoteFormatter.formatUserContent("正文", listOf(quote())))
            .isEqualTo("> 引文\n\n批注\n\n正文")
    }

    @Test
    fun `多段多行引文保留中间空行并去掉首尾空白行`() {
        val quotes = listOf(quote("\n  \r\n第一行\r\n\t\r\n第三行\r\n ", "  批注一\n "), quote("第二段", "批注二"))
        assertThat(MessageQuoteFormatter.formatUserContent("整体再精简一些", quotes))
            .isEqualTo("> 第一行\n>\n> 第三行\n\n批注一\n\n> 第二段\n\n批注二\n\n整体再精简一些")
    }

    @Test
    fun `只有引用时不留多余空行`() {
        assertThat(MessageQuoteFormatter.formatUserContent(" \n ", listOf(quote())))
            .isEqualTo("> 引文\n\n批注")
    }

    @Test
    fun `没有有效引用时正文逐字保留`() {
        listOf(null, emptyList(), listOf(quote(" "), quote(annotation = "\n"))).forEach { quotes ->
            assertThat(MessageQuoteFormatter.formatUserContent(" \n正文\n ", quotes)).isEqualTo(" \n正文\n ")
        }
    }

    @Test
    fun `有效引用之间跳过缺引文或批注的项`() {
        assertThat(MessageQuoteFormatter.formatUserContent("", listOf(quote(""), quote(), quote(annotation = ""))))
            .isEqualTo("> 引文\n\n批注")
    }

    @Test
    fun `引文行内空白和正文缩进保留且末尾没有空白行`() {
        assertThat(MessageQuoteFormatter.formatUserContent("  正文\n\n", listOf(quote("  代码  "))))
            .isEqualTo(">   代码  \n\n批注\n\n  正文")
    }
}
