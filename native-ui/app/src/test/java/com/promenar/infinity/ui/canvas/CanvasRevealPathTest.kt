package com.promenar.infinity.ui.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasWorkspaceSync
import com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination
import com.promenar.infinity.data.workspace.WorkspaceFileDestinationBinding
import org.junit.Test

class CanvasRevealPathTest {
    @Test fun `私有空间画布在主工作区就是私有空间时可定位`() {
        val metadata = canvas("canvas/a.html", WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.SESSION_PRIVATE))
        assertThat(canvasRevealPath(metadata, primaryWorkspaceId = null)).isEqualTo("/canvas/a.html")
    }

    @Test fun `私有空间画布在主工作区是登记项目时不提供定位`() {
        val metadata = canvas("canvas/a.html", WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.SESSION_PRIVATE))
        assertThat(canvasRevealPath(metadata, primaryWorkspaceId = "project-1")).isNull()
    }

    @Test fun `主工作区画布只在仍是首次绑定的工作区时可定位`() {
        val metadata = canvas("canvas/a.html", WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, "project-1"))
        assertThat(canvasRevealPath(metadata, primaryWorkspaceId = "project-1")).isEqualTo("/canvas/a.html")
        assertThat(canvasRevealPath(metadata, primaryWorkspaceId = "project-2")).isNull()
        assertThat(canvasRevealPath(metadata, primaryWorkspaceId = null)).isNull()
    }

    @Test fun `已知运行模式前缀还原为主工作区相对路径`() {
        listOf("/home/session/canvas/a.html", "/workspace/canvas/a.html", "/session/canvas/a.html", "/canvas/a.html").forEach { path ->
            assertThat(canvasRevealPath(canvas(path, binding = null), primaryWorkspaceId = null)).isEqualTo("/canvas/a.html")
        }
    }

    @Test fun `镜像缺失或未写出时不提供定位`() {
        assertThat(canvasRevealPath(canvas("canvas/a.html", null, CanvasWorkspaceSync.MISSING), null)).isNull()
        assertThat(canvasRevealPath(canvas(null, null), null)).isNull()
    }

    private fun canvas(
        path: String?,
        binding: WorkspaceFileDestinationBinding?,
        sync: CanvasWorkspaceSync = CanvasWorkspaceSync.SYNCED,
    ) = CanvasMetadata(
        id = "a", title = "A", createdAt = 0, updatedAt = 0, currentVersion = 1, createdByMessageId = "", versions = listOf(1),
        workspacePath = path, workspaceSync = sync, workspaceDestination = binding,
    )
}
