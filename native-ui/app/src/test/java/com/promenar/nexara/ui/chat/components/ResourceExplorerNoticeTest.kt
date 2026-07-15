package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.status.NoticeSeverity
import org.junit.Test

class ResourceExplorerNoticeTest {

    @Test
    fun `workspace load failed maps code and severity`() {
        val notice = ResourceExplorerNotice.workspaceLoadFailed("io exception")
        assertThat(notice.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(notice.technical).isEqualTo("io exception")
    }

    @Test
    fun `workspace not ready maps code and severity`() {
        val notice = ResourceExplorerNotice.workspaceNotReady()
        assertThat(notice.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_NOT_READY)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Error)
    }

    @Test
    fun `template resolves known codes`() {
        assertThat(
            ResourceExplorerNotice.template(
                ResourceExplorerNotice.workspaceLoadFailed("io exception")
            )?.resourceId
        ).isEqualTo(
            R.string.resource_explorer_error_workspace_load_failed
        )
        assertThat(
            ResourceExplorerNotice.template(
                ResourceExplorerNotice.workspaceNotReady()
            )?.resourceId
        ).isEqualTo(
            R.string.resource_explorer_error_workspace_not_ready
        )
    }

    @Test
    fun `unknown code returns null template`() {
        val unknown = ResourceExplorerNotice.workspaceLoadFailed(null).copy(code = "unknown")
        assertThat(ResourceExplorerNotice.template(unknown)).isNull()
    }
}
