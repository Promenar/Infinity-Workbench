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

    @Test fun `默认落点提示区分技能挂载与用户挂载且支持运行模式呈现`() {
        val workspace = com.promenar.nexara.data.workspace.SessionWorkspace.Directory(
            com.promenar.nexara.data.local.db.entity.WorkspaceEntity("ws", "project", "项目", "project", 1), java.io.File("project"))
        val skills = com.promenar.nexara.domain.storage.ExternalMount("builtin:skills", "skills", "builtin_skills", "agent_skills", "s", 0)
        val assets = com.promenar.nexara.domain.storage.ExternalMount("assets", "素材", "real_path", "/assets", "s", 0)
        val main = SystemPromptSections.toolGuidance(setOf("canvas_create", "media_fetch"), false, workspace, mounts = listOf(skills))
        assertThat(main).contains("默认保存在主工作区（`/`）")
        val privatePrompt = SystemPromptSections.toolGuidance(setOf("canvas_create", "media_fetch"), false, workspace, mounts = listOf(skills, assets))
        assertThat(privatePrompt).contains("默认保存在会话专用空间（`/session`）")
        assertThat(privatePrompt).contains("切换主工作区不会搬动镜像")
        val linux = SystemPromptSections.toolGuidance(setOf("canvas_create"), false, workspace, mounts = listOf(assets),
            filePathPresenter = com.promenar.nexara.data.workspace.WorkspaceFilePathPresenter { target, path, _ ->
                if (target == com.promenar.nexara.data.workspace.WorkspaceDefaultFileDestination.SESSION_PRIVATE) "/home/session" + path.trimEnd('/') else path
            })
        assertThat(linux).contains("默认保存在会话专用空间（`/home/session`）")
        assertThat(linux).doesNotContain("通过 `/session`")
    }
    @Test fun `Linux持久模式的默认落点和私有入口不泄漏内置session路径`() {
        val workspace = com.promenar.nexara.data.workspace.SessionWorkspace.Directory(
            com.promenar.nexara.data.local.db.entity.WorkspaceEntity("ws", "project", "项目", "project", 1), java.io.File("project"))
        val prompt = SystemPromptSections.toolGuidance(setOf("media_fetch"), false, workspace,
            runMode = com.promenar.nexara.domain.model.SessionRunMode.LINUX)
        assertThat(prompt).contains("默认保存在主工作区（`/workspace`）")
        assertThat(prompt).contains("通过 `/home/session`")
        assertThat(prompt).doesNotContain("`/session`")
    }

}
