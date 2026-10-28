package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.skills.PythonExecSkill
import com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Test

class WorkspaceEnvironmentAliasesTest {
    @Test fun `Linux命令构造时为普通和分支起始目录导出兼容别名`() {
        for (directory in listOf(null, "/worktrees/feature")) {
            val environment = linuxExecSpec("pwd", File("workspace"), "project", emptyList(), File("skills"),
                workspaceDirectory = directory).environment
            assertThat(environment["INFINITY_WORKSPACE"]).isEqualTo(directory ?: "/workspace")
            assertThat(environment["INFINITY_WORKSPACE_KIND"]).isEqualTo("project")
            assertThat(environment["INFINITY_SKILLS"]).isEqualTo("/mnt/skills")
            for (suffix in listOf("WORKSPACE", "WORKSPACE_KIND", "SKILLS")) {
                assertThat(environment["NEXARA_$suffix"]).isEqualTo(environment["INFINITY_$suffix"])
            }
        }
    }

    @Test fun `不存在的新变量不会从过期旧别名派生且输入不被修改`() {
        val source = mapOf("NEXARA_SKILLS" to "/stale", "PATH" to "/bin")
        assertThat(source.withLegacyWorkspaceAliases()).containsExactly("PATH", "/bin")
        assertThat(source).containsEntry("NEXARA_SKILLS", "/stale")
    }

    @Test fun `内置技能描述和系统提示词仅公布新名`() {
        val service = mockk<BuiltinExecService> { every { pipAvailable } returns true }
        val descriptions = listOf(ShellExecSkill(service).description, PythonExecSkill(service).description) +
            SessionRunMode.entries.map { mode ->
                SystemPromptSections.toolGuidance(setOf("shell_exec", "python_exec", "read_file"), false, runMode = mode)
            } + SystemPromptSections.LINUX_SHELL_GUIDANCE
        descriptions.forEach { description ->
            assertThat(description).doesNotContain("NEXARA_")
        }
        descriptions.take(2).forEach { description ->
            assertThat(description).contains("INFINITY_WORKSPACE")
            assertThat(description).contains("INFINITY_SKILLS")
        }
    }
}
