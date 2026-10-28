package com.promenar.infinity.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import com.promenar.infinity.ui.renderer.infinityMarkdownBodyStyle
import com.promenar.infinity.ui.renderer.infinityMarkdownCodeStyle
import com.promenar.infinity.ui.renderer.infinityMarkdownHeadingStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class InfinityMarkdownTypographyTest {
    @Test
    fun `正文跟随会话字号并保留一点六倍内部行高且不叠加块外留白`() {
        val style = infinityMarkdownBodyStyle(15)
        assertEquals(15f, style.fontSize.value, 0.001f)
        assertEquals(24f, style.lineHeight.value, 0.001f)
        assertEquals(InfinityReadingLineBreak, style.lineBreak)
        assertEquals(LineHeightStyle.Trim.Both, style.lineHeightStyle?.trim)
    }

    @Test
    fun `标题字号层级半粗与一点三五倍行高适用于六级标题`() {
        val sizes = listOf(20.25f, 18f, 16.5f, 15f, 15f, 15f)
        sizes.forEachIndexed { index, size ->
            val style = infinityMarkdownHeadingStyle(index + 1, 15)
            assertEquals(size, style.fontSize.value, 0.001f)
            assertEquals(size * 1.35f, style.lineHeight.value, 0.001f)
            assertEquals(FontWeight.SemiBold, style.fontWeight)
        }
    }

    @Test
    fun `代码始终使用等宽十三号与二十号行高`() {
        val style = infinityMarkdownCodeStyle()
        assertEquals(InfinityMonospace, style.fontFamily)
        assertEquals(13f, style.fontSize.value, 0.001f)
        assertEquals(20f, style.lineHeight.value, 0.001f)
        assertEquals(InfinityInputLineBreak, style.lineBreak)
    }
}
