package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemPromptSectionsMediaTest {
    @Test fun `工作区执行与媒体工具单独可用都提供媒体约定`() {
        listOf("read_file", "write_file", "list_files", "shell_exec", "python_exec", "run_script", "exec_js", "python_sandbox", "termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "image_generation", "generate_image", "media_fetch", "view_media").forEach { name ->
            val prompt = SystemPromptSections.toolGuidance(setOf(name), false)
            assertThat(prompt).contains("### Media")
            assertThat(prompt).contains("media/<yyyy-MM-dd>/")
            assertThat(prompt).contains("![brief description](workspace path)")
            assertThat(prompt).contains("[name](workspace path)")
            assertThat(prompt).contains("base64")
            assertThat(prompt).contains("tailnet")
        }
    }
    @Test fun `无媒体相关工具与generic不增加分节`() {
        listOf(emptySet(), setOf("calculator"), setOf("web_fetch"), setOf("get_plan")).forEach {
            assertThat(SystemPromptSections.toolGuidance(it, false)).doesNotContain("### Media")
        }
        assertThat(SystemPromptSections.toolGuidance(setOf("media_fetch", "view_media"), true)).doesNotContain("### Media")
    }
    @Test fun `查看与下载指引仅在提供对应工具时出现`() {
        val basic = SystemPromptSections.toolGuidance(setOf("read_file"), false)
        assertThat(basic).doesNotContain("`view_media`")
        assertThat(basic).doesNotContain("`media_fetch`")
        val full = SystemPromptSections.toolGuidance(setOf("view_media", "media_fetch"), false)
        listOf("`view_media`", "`frames`", "`interval_seconds`", "time range", "`media_fetch`", "tool steps").forEach { assertThat(full).contains(it) }
    }
    @Test fun `其它分节内容与已锁定基线一致`() {
        val actual = SystemPromptSections.toolGuidance(setOf("read_file", "initialize_plan"), false)
        val expected = javaClass.getResource("/media-d-tool-guidance-before.txt")!!.readText()
        assertThat(actual.substringBefore("\n### Media").trimEnd()).isEqualTo(expected.trimEnd())
    }
}
