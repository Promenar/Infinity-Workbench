package com.promenar.infinity.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.SessionWorkspace
import java.io.File
import org.junit.Test

/** 会话设置“工作区”项的展示数据：私有、目录与不可用三种状态。 */
class SessionWorkspaceUiTest {
    private val project = WorkspaceEntity("p", WorkspaceEntity.KIND_PROJECT, "演示", "demo", 1)
    private val shared = WorkspaceEntity("s", WorkspaceEntity.KIND_SHARED, "仓库", "/storage/emulated/0/Code/repo", 1)

    @Test fun `私有工作区映射为专用空间`() {
        val ui = SessionWorkspace.Private.toUi()
        assertThat(ui).isEqualTo(SessionWorkspaceUi.Private)
    }

    @Test fun `目录工作区显示登记名、类型与位置而不是安装路径`() {
        val ui = SessionWorkspace.Directory(project, File("/data/user/0/app/files/projects/demo")).toUi()
        assertThat(ui).isEqualTo(SessionWorkspaceUi.Directory("p", "演示", "project", "demo"))
        assertThat(SessionWorkspace.Directory(shared, File(shared.location)).toUi())
            .isEqualTo(SessionWorkspaceUi.Directory("s", "仓库", "shared", "/storage/emulated/0/Code/repo"))
    }

    @Test fun `不可用时保留登记信息与原因，登记已删除时只有原因`() {
        val unavailable = SessionWorkspace.Unavailable("s", shared, "请先授予所有文件访问权限").toUi()
        assertThat(unavailable).isEqualTo(
            SessionWorkspaceUi.Unavailable("s", "仓库", "shared", "/storage/emulated/0/Code/repo", "请先授予所有文件访问权限"),
        )
        val deleted = SessionWorkspace.Unavailable("gone", null, "工作区登记已删除").toUi() as SessionWorkspaceUi.Unavailable
        assertThat(deleted.name).isNull()
        assertThat(deleted.kind).isNull()
        assertThat(deleted.reason).isEqualTo("工作区登记已删除")
    }

    @Test fun `每类工作区两条标签依次说备份与何时消失，只有会被清掉的用提醒色`() {
        fun tags(kind: String?) = workspaceKindTags(kind).map { it.label to it.tone }
        assertThat(tags(null)).containsExactly(
            R.string.workspace_tag_backed_up to WorkspaceTagTone.NEUTRAL,
            R.string.workspace_tag_kept_on_delete to WorkspaceTagTone.NEUTRAL,
        ).inOrder()
        assertThat(tags(WorkspaceEntity.KIND_PROJECT)).containsExactly(
            R.string.workspace_tag_not_backed_up to WorkspaceTagTone.NEUTRAL,
            R.string.workspace_tag_gone_on_uninstall to WorkspaceTagTone.CAUTION,
        ).inOrder()
        assertThat(tags(WorkspaceEntity.KIND_LINUX_HOME)).containsExactly(
            R.string.workspace_tag_not_backed_up to WorkspaceTagTone.NEUTRAL,
            R.string.workspace_tag_cleared_on_reset to WorkspaceTagTone.CAUTION,
        ).inOrder()
        assertThat(tags(WorkspaceEntity.KIND_SHARED)).containsExactly(
            R.string.workspace_tag_not_backed_up to WorkspaceTagTone.NEUTRAL,
            R.string.workspace_tag_kept_on_uninstall to WorkspaceTagTone.NEUTRAL,
        ).inOrder()
    }
}
