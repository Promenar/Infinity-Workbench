package com.promenar.nexara.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.common.FolderCrumb
import java.io.File
import org.junit.Test

/** 选择器面包屑与单行位置文本：深层目录保留当前目录可见，点击面包屑回到对应层级。 */
class WorkspacePickerNavigationTest {
    private val volume = File("/storage/emulated/0")
    private val deep = WorkspacePickerPage.Shared(volume, File(volume, "Documents/Projects/thesis"))

    @Test fun `共享卷下的路径段逐级转为面包屑`() {
        val crumbs = deep.crumbs({ "共享存储" }, "项目")
        assertThat(crumbs.map { it.name }).containsExactly("共享存储", "Documents", "Projects", "thesis").inOrder()
        assertThat(crumbs.last().id).isEqualTo("/storage/emulated/0/Documents/Projects/thesis")
        assertThat(WorkspacePickerPage.Projects.crumbs({ "" }, "项目").map { it.name }).containsExactly("项目")
    }

    @Test fun `深层面包屑折叠为首级、省略号与当前目录，省略号指向上一级`() {
        val shown = deep.crumbs({ "共享存储" }, "项目").collapsedForDisplay()
        assertThat(shown.map { it.name }).containsExactly("共享存储", "…", "thesis").inOrder()
        assertThat(deep.navigateToCrumb(shown[1].id)).isEqualTo(deep.copy(path = File(volume, "Documents/Projects")))
        val shallow = listOf(FolderCrumb("a", "a"), FolderCrumb("b", "b"), FolderCrumb("c", "c"))
        assertThat(shallow.collapsedForDisplay()).isEqualTo(shallow)
    }

    @Test fun `点击面包屑回到对应层级，根级与卷外路径不越界`() {
        assertThat(deep.navigateToCrumb(null)).isEqualTo(WorkspacePickerPage.Home)
        assertThat(deep.navigateToCrumb(volume.path)).isEqualTo(deep.copy(path = volume))
        assertThat(deep.navigateToCrumb("/data/user/0")).isEqualTo(deep)
        assertThat(WorkspacePickerPage.Projects.navigateToCrumb("projects")).isEqualTo(WorkspacePickerPage.Projects)
        assertThat(deep.parent()).isEqualTo(deep.copy(path = File(volume, "Documents/Projects")))
        assertThat(deep.copy(path = volume).parent()).isEqualTo(WorkspacePickerPage.Home)
    }

    @Test fun `深层共享路径只保留最后两级`() {
        assertThat(compactSharedPath("/storage/emulated/0/Documents/Projects/thesis/system")).isEqualTo("…/thesis/system")
        assertThat(compactSharedPath("/storage/emulated/0")).isEqualTo("/storage/emulated/0")
    }
}
