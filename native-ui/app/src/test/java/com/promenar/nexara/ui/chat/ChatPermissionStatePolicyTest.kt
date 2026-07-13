package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatPermissionStatePolicyTest {
    @Test
    fun `系统权限请求已在进行时重建不得回执拒绝`() {
        assertThat(
            notificationPermissionDisposition(
                requestTaskId = "task-1",
                granted = false,
                shouldExplain = false,
                inFlightTaskId = "task-1",
            ),
        ).isEqualTo(NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT)
    }

    @Test
    fun `launcher回调优先使用跨重建保存的inFlight taskId`() {
        assertThat(
            notificationPermissionResultTaskId(
                inFlightTaskId = "task-stable",
                explanationTaskId = null,
            ),
        ).isEqualTo("task-stable")
    }

    @Test
    fun `未请求过时展示解释而不直接拒绝`() {
        assertThat(
            notificationPermissionDisposition(
                requestTaskId = "task-2",
                granted = false,
                shouldExplain = true,
                inFlightTaskId = null,
            ),
        ).isEqualTo(NotificationPermissionDisposition.SHOW_EXPLANATION)
    }
}
