package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.dp
import org.junit.Test

class ChatComposerInsetsTest {
    @Test
    fun `输入区高度统一驱动列表流式尾部与回底按钮避让`() {
        val insets = chatComposerInsets(132.dp)

        assertThat(insets.contentBottom).isEqualTo(156.dp)
        assertThat(insets.streamingOverlap).isEqualTo(148.dp)
        assertThat(insets.fabBottom).isEqualTo(148.dp)
    }

    @Test
    fun `未完成首次测量时仍提供安全避让`() {
        val insets = chatComposerInsets(0.dp)

        assertThat(insets.contentBottom).isEqualTo(120.dp)
        assertThat(insets.streamingOverlap).isEqualTo(112.dp)
        assertThat(insets.fabBottom).isEqualTo(112.dp)
    }
}
