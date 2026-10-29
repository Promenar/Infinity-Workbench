package com.promenar.infinity.ui.chat.quote

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class QuoteCapturingClipboardTest {
    private val clipboard = QuoteCapturingClipboard(mockk(relaxed = true))

    @Test
    fun `登记后的下一次复制交给引用，不写系统剪贴板`() {
        var captured: String? = null
        clipboard.captureNext(now = 1_000L) { captured = it }

        assertThat(clipboard.consume("选中文字", now = 1_500L)).isTrue()
        assertThat(captured).isEqualTo("选中文字")
        // 只截获一次，之后的复制照常写入。
        assertThat(clipboard.consume("再复制", now = 1_600L)).isFalse()
    }

    @Test
    fun `登记超过两秒未复制即作废`() {
        var captured: String? = null
        clipboard.captureNext(now = 1_000L) { captured = it }

        assertThat(clipboard.consume("普通复制", now = 3_500L)).isFalse()
        assertThat(captured).isNull()
    }

    @Test
    fun `未登记时复制照常写入`() {
        assertThat(clipboard.consume("普通复制", now = 0L)).isFalse()
    }
}
