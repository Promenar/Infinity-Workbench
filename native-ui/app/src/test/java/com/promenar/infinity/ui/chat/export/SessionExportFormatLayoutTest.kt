package com.promenar.infinity.ui.chat.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionExportFormatLayoutTest {
    @Test fun `按自然宽度分行，放不下才换行，超宽项独占一行`() {
        // 360 宽，间距 8：前四项 90+60+60+60+3*8=294 放得下，第五项 70 放不下换行。
        assertThat(packNaturalWidthLines(listOf(90, 60, 60, 60, 70), gap = 8, maxWidth = 300))
            .containsExactly(listOf(0, 1, 2, 3), listOf(4)).inOrder()
        assertThat(packNaturalWidthLines(listOf(500, 40, 40), gap = 8, maxWidth = 300))
            .containsExactly(listOf(0), listOf(1, 2)).inOrder()
        assertThat(packNaturalWidthLines(emptyList(), gap = 8, maxWidth = 300)).isEmpty()
    }

    @Test fun `每行剩余宽度平均分给本行各项，不小于自然宽度且正好铺满`() {
        val natural = listOf(110, 60, 60, 60)
        val stretched = stretchLine(natural, gap = 8, maxWidth = 335)
        assertThat(stretched.sum() + 8 * 3).isEqualTo(335)
        stretched.zip(natural).forEach { (width, min) -> assertThat(width).isAtLeast(min) }
        // 最宽的 Markdown 仍比其它项宽，不会被压成等宽。
        assertThat(stretched[0]).isGreaterThan(stretched[1])
        // 单项一行时占满整行。
        assertThat(stretchLine(listOf(70), gap = 8, maxWidth = 335)).containsExactly(335)
        // 自然宽度已超出时不缩小。
        assertThat(stretchLine(listOf(400), gap = 8, maxWidth = 335)).containsExactly(400)
    }
}
