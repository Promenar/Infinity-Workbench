package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemPromptPreviewTest {
    @Test
    fun `空白提示词没有预览`() {
        assertThat(systemPromptPreview("")).isNull()
        assertThat(systemPromptPreview(" \n\t \n")).isNull()
    }

    @Test
    fun `去掉空行并合并空白`() {
        assertThat(systemPromptPreview("  你是助手。\n\n  回答要简洁，\t使用中文。  "))
            .isEqualTo("你是助手。 回答要简洁， 使用中文。")
    }

    @Test
    fun `过长时截断并加省略号`() {
        val preview = systemPromptPreview("a".repeat(200))!!
        assertThat(preview).hasLength(121)
        assertThat(preview).endsWith("…")
    }
}
