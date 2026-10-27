package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasSystemPromptTest {
    @Test fun `只读画布工具不引导调用未提供的写入工具`() {
        val prompt = SystemPromptSections.toolGuidance(setOf("canvas_read", "canvas_list"), false)
        assertThat(prompt).contains("canvas_read")
        assertThat(prompt).contains("canvas_list")
        assertThat(prompt).doesNotContain("canvas_create")
        assertThat(prompt).doesNotContain("canvas_update")
    }

    @Test fun `未提供画布工具或generic模式不注入画布指引`() {
        assertThat(SystemPromptSections.toolGuidance(setOf("calculator"), false)).doesNotContain("会话画布")
        assertThat(SystemPromptSections.toolGuidance(setOf("canvas_create"), true)).doesNotContain("会话画布")
    }
    @Test fun `原型和产物指引仅在工具可用时出现`() {
        val prompt = SystemPromptSections.toolGuidance(setOf("canvas_create", "artifact_publish"), false)
        assertThat(prompt).contains("nexara-screen:")
        assertThat(prompt).contains("artifact_publish")
        val absent = SystemPromptSections.toolGuidance(setOf("canvas_read"), false)
        assertThat(absent).doesNotContain("nexara-screen:")
        assertThat(absent).doesNotContain("artifact_publish")
    }

}
