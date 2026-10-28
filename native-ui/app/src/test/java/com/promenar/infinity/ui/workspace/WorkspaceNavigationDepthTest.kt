package com.promenar.infinity.ui.workspace

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** 层级深度决定进入与返回的切换方向；上一级路径决定系统返回落点。 */
class WorkspaceNavigationDepthTest {

    @Test
    fun `工作区选择器的层级随共享目录逐级加深`() {
        val volume = File("/storage/emulated/0")

        assertThat(WorkspacePickerPage.Home.depth).isEqualTo(0)
        assertThat(WorkspacePickerPage.Projects.depth).isEqualTo(1)
        assertThat(WorkspacePickerPage.Shared(volume, volume).depth).isEqualTo(1)
        assertThat(WorkspacePickerPage.Shared(volume, File(volume, "Documents")).depth).isEqualTo(2)
        assertThat(WorkspacePickerPage.Shared(volume, File(volume, "Documents/notes/2026")).depth).isEqualTo(4)
    }

    @Test
    fun `工作区上一级路径在根目录为空`() {
        assertThat(parentWorkspacePath("/")).isNull()
        assertThat(parentWorkspacePath("")).isNull()
        assertThat(parentWorkspacePath("/skills")).isEqualTo("/")
        assertThat(parentWorkspacePath("/skills-accept/.agents/skills")).isEqualTo("/skills-accept/.agents")
        assertThat(parentWorkspacePath("/a/b/")).isEqualTo("/a")
    }
}
