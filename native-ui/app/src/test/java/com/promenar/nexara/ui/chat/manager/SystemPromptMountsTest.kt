package com.promenar.nexara.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.domain.storage.ExternalMount
import org.junit.Test

class SystemPromptMountsTest {
    private fun mount(alias: String, type: String, source: String, unavailable: String? = null) =
        ExternalMount("id-$alias", alias, type, source, "s1", createdAt = 1, unavailableReason = unavailable)

    @Test fun `挂载段落区分命令可见性并隐藏技能目录`() {
        val text = SystemPromptSections.toolGuidance(
            setOf("read_file", "shell_exec"),
            generic = false,
            mounts = listOf(
                mount("termux", "saf_tree", "content://tree"),
                mount("docs", "real_path", "/storage/emulated/0/Documents"),
                mount("gone", "real_path", "/storage/emulated/0/Old", unavailable = "missing"),
                ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, ExternalMountPaths.SKILLS_ALIAS, ExternalMountPaths.SKILLS_SOURCE, "", "s1", 1),
            ),
        )
        assertThat(text).contains("### Mounted Folders")
        assertThat(text).contains("`/mnt/docs/`: commands reach it at the real path `/storage/emulated/0/Documents`.")
        assertThat(text).contains("`/mnt/termux/`: file tools only.")
        assertThat(text).contains("`/mnt/gone/`: currently unavailable")
        assertThat(text).doesNotContain("/storage/emulated/0/Old")
        assertThat(text).doesNotContain("/mnt/skills/")
    }

    @Test fun `没有挂载或没有工作区工具时不输出挂载段落`() {
        assertThat(SystemPromptSections.toolGuidance(setOf("read_file"), generic = false)).doesNotContain("Mounted Folders")
        val docs = listOf(mount("docs", "real_path", "/storage/emulated/0/Documents"))
        assertThat(SystemPromptSections.toolGuidance(setOf("web_search"), generic = false, mounts = docs)).doesNotContain("Mounted Folders")
    }

    @Test fun `知识库在普通与Linux提示中均显示只读且不暴露物理路径`() {
        val knowledge = mount("knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "root-uuid").copy(readOnly = true)
        for (linux in listOf(false, true)) {
            val text = SystemPromptSections.toolGuidance(setOf("read_file"), generic = false, mounts = listOf(knowledge),
                runMode = if (linux) com.promenar.nexara.domain.model.SessionRunMode.LINUX else com.promenar.nexara.domain.model.SessionRunMode.BUILTIN)
            assertThat(text).contains("`/mnt/knowledge/`：只读")
            assertThat(text).contains("命令无法访问知识库挂载")
            assertThat(text).doesNotContain("root-uuid")
            assertThat(text).doesNotContain("may read, write and delete")
        }
    }
}
