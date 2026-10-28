package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceDefaultFileDestinationTest {
    private val skills = ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "agent_skills", "s", 0)
    private val mount = ExternalMount("user", "素材", "real_path", "/assets", "s", 0)
    private fun directory(kind: String) = SessionWorkspace.Directory(WorkspaceEntity("ws", kind, "工作区", "project", 1), File("project"))

    @Test fun `所有登记类型在没有用户挂载时使用主工作区且技能挂载不计入`() {
        for (kind in listOf(WorkspaceEntity.KIND_PROJECT, WorkspaceEntity.KIND_SHARED, WorkspaceEntity.KIND_LINUX_HOME)) {
            assertThat(defaultWorkspaceFileDestination(directory(kind), emptyList())).isEqualTo(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE)
            assertThat(defaultWorkspaceFileDestination(directory(kind), listOf(skills))).isEqualTo(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE)
        }
    }

    @Test fun `私有主根和任意用户挂载均默认使用会话专用空间`() {
        assertThat(defaultWorkspaceFileDestination(SessionWorkspace.Private, emptyList())).isEqualTo(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
        assertThat(defaultWorkspaceFileDestination(SessionWorkspace.Private, listOf(skills, mount))).isEqualTo(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
        assertThat(defaultWorkspaceFileDestination(directory(WorkspaceEntity.KIND_PROJECT), listOf(skills, mount))).isEqualTo(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
        assertThat(defaultWorkspaceFileDestination(directory(WorkspaceEntity.KIND_PROJECT), listOf(mount.copy(unavailableReason = "离线")))).isEqualTo(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
    }

    @Test fun `不可用主工作区不会被当作私有默认回退`() {
        assertThrows(ExternalFolderException::class.java) { defaultWorkspaceFileDestination(SessionWorkspace.Unavailable("ws", null, "离线"), listOf(mount)) }
    }

    @Test fun `内置呈现保留私有主根路径并为独立私有目标加session前缀`() {
        assertThat(BuiltinWorkspaceFilePathPresenter.present(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, "media/a.png", false)).isEqualTo("/media/a.png")
        assertThat(BuiltinWorkspaceFilePathPresenter.present(WorkspaceDefaultFileDestination.SESSION_PRIVATE, "media/a.png", true)).isEqualTo("/media/a.png")
        assertThat(BuiltinWorkspaceFilePathPresenter.present(WorkspaceDefaultFileDestination.SESSION_PRIVATE, "media/a.png", false)).isEqualTo("/session/media/a.png")
        assertThat(BuiltinWorkspaceFilePathPresenter.privateRelativePath("/session/media/a.png")).isEqualTo("/media/a.png")
        assertThat(BuiltinWorkspaceFilePathPresenter.privateRelativePath("/session-other/a.png")).isNull()
    }

    @Test fun `路径呈现可替换为Linux私有根且不改变目标判定`() {
        val presenter = WorkspaceFilePathPresenter { target, relative, _ ->
            if (target == WorkspaceDefaultFileDestination.SESSION_PRIVATE) "/home/session/${relative.trimStart('/')}" else relative
        }
        val target = defaultWorkspaceFileDestination(directory(WorkspaceEntity.KIND_PROJECT), listOf(mount))
        assertThat(presenter.present(target, "media/a.png", false)).isEqualTo("/home/session/media/a.png")
    }

    @Test fun `私有虚拟路径不绑定主工作区挂载且技能路径仍需逐次审批`() {
        assertThat(ExternalMountPaths.aliasOf("/session/media/a.png", rooted = true)).isNull()
        assertThat(ExternalMountPaths.aliasOf("/session-other/media/a.png", rooted = true)).isEqualTo(ExternalMountPaths.WORKSPACE_ALIAS)
        assertThat(com.promenar.infinity.domain.tool.ToolExecutionTargets.requiresSkillsApproval("write_file", """["path","/session/.agents/skills/demo/SKILL.md"]""")).isTrue()
    }
    @Test fun `Linux呈现双向识别精确根且模式选择不依赖偏好`() {
        val linux = workspaceFilePathPresenter(com.promenar.infinity.domain.model.SessionRunMode.LINUX)
        assertThat(linux).isSameInstanceAs(LinuxWorkspaceFilePathPresenter)
        for (mode in listOf(null, com.promenar.infinity.domain.model.SessionRunMode.BUILTIN)) {
            assertThat(workspaceFilePathPresenter(mode)).isSameInstanceAs(BuiltinWorkspaceFilePathPresenter)
        }
        for (relative in listOf("/", "/media/a.png")) {
            for (destination in WorkspaceDefaultFileDestination.entries) {
                for (privatePrimary in listOf(true, false)) {
                    val path = linux.present(destination, relative, privatePrimary)
                    val prefix = if (privatePrimary || destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE) "/home/session" else "/workspace"
                    assertThat(path).isEqualTo(prefix + relative.takeUnless { it == "/" }.orEmpty())
                    assertThat(linux.privateRelativePath(path)).isEqualTo(relative)
                }
            }
        }
        for (path in listOf("/home/session-other/x", "/workspace-other/x", "/session/x", "/root/x")) {
            assertThat(linux.privateRelativePath(path)).isNull()
        }
    }

}
