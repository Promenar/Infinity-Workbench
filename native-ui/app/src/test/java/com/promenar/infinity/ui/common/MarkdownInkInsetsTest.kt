package com.promenar.infinity.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInkInsetsTest {
    @Test
    fun `首末字形外留白被裁去而中间基线跨度保持`() {
        assertEquals(ReadingInkInsets(8, 7), readingInkInsets(72, 8.7f, 64.2f))
        assertEquals(57, 72 - 8 - 7)
    }

    @Test
    fun `向外取整保留抗锯齿边缘且不产生负高度`() {
        assertEquals(ReadingInkInsets(0, 0), readingInkInsets(24, -0.2f, 24.2f))
        assertEquals(ReadingInkInsets(24, 0), readingInkInsets(24, 30f, 40f))
    }
}
