package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatOverlayPolicyTest {
    @Test
    fun `待处理通知权限时会排队业务模态层`() {
        assertThat(
            enqueueChatOverlay(
                state = ChatOverlayQueueState(),
                requested = ChatOverlay.ClearHistory,
                hasPendingNotificationPermission = true,
            ),
        ).isEqualTo(
            ChatOverlayQueueState(
                active = null,
                queued = ChatOverlay.ClearHistory,
            ),
        )
    }

    @Test
    fun `已有聊天模态层时会排队后续请求`() {
        assertThat(
            enqueueChatOverlay(
                state = ChatOverlayQueueState(active = ChatOverlay.Truncate("message-1")),
                requested = ChatOverlay.DeleteSession,
                hasPendingNotificationPermission = false,
            ),
        ).isEqualTo(
            ChatOverlayQueueState(
                active = ChatOverlay.Truncate("message-1"),
                queued = ChatOverlay.DeleteSession,
            ),
        )
    }

    @Test
    fun `无冲突时允许打开截断确认并保留目标消息`() {
        assertThat(
            enqueueChatOverlay(
                state = ChatOverlayQueueState(),
                requested = ChatOverlay.Truncate("message-2"),
                hasPendingNotificationPermission = false,
            ),
        ).isEqualTo(ChatOverlayQueueState(active = ChatOverlay.Truncate("message-2")))
    }

    @Test
    fun `权限流程结束后自动提升排队的模态层`() {
        assertThat(
            advanceChatOverlay(
                state = ChatOverlayQueueState(queued = ChatOverlay.RenameSession),
                hasPendingNotificationPermission = false,
            ),
        ).isEqualTo(ChatOverlayQueueState(active = ChatOverlay.RenameSession))
    }

    @Test
    fun `模态层令牌可跨重建往返恢复截断目标`() {
        val overlay = ChatOverlay.Truncate("message:with:separator")

        assertThat(restoreChatOverlay(saveChatOverlay(overlay))).isEqualTo(overlay)
    }
}
