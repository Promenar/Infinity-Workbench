package com.promenar.nexara.ui.common

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInlineCodeInkTest {
    @Test
    fun `真实行内占位注解不参与墨迹测量且用户替换字符保留`() {
        val text = buildAnnotatedString {
            append("正文")
            appendInlineContent(INLINE_CODE_PADDING_ID)
            append("code")
            appendInlineContent(INLINE_CODE_PADDING_ID)
            append("真实�")
        }
        assertEquals("正文code真实�", readingInkLineText(text, 0, text.length))
    }

    @Test
    fun `没有占位注解的用户原文逐字符保留`() {
        val text = buildAnnotatedString { append("原文�与\uFFFC占位外形字符") }
        assertEquals(text.text, readingInkLineText(text, 0, text.length))
    }
}
