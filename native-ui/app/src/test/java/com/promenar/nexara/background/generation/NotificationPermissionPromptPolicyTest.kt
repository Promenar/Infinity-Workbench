package com.promenar.nexara.background.generation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationPermissionPromptPolicyTest {
    @Test
    fun `仅Android13以上首次拒绝状态需要上下文解释`() {
        assertThat(
            shouldExplainNotificationPermission(
                NotificationPermissionPromptState(33, granted = false, alreadyAsked = false),
            ),
        ).isTrue()
        assertThat(
            shouldExplainNotificationPermission(
                NotificationPermissionPromptState(32, granted = false, alreadyAsked = false),
            ),
        ).isFalse()
        assertThat(
            shouldExplainNotificationPermission(
                NotificationPermissionPromptState(35, granted = true, alreadyAsked = false),
            ),
        ).isFalse()
        assertThat(
            shouldExplainNotificationPermission(
                NotificationPermissionPromptState(35, granted = false, alreadyAsked = true),
            ),
        ).isFalse()
    }

    @Test
    fun `权限说明仅在有待处理请求且没有其它弹层时显示`() {
        assertThat(
            shouldShowNotificationPermissionDialog(
                NotificationPermissionOverlayState(
                    hasPendingRequest = true,
                    hasBlockingOverlay = false,
                ),
            ),
        ).isTrue()
        assertThat(
            shouldShowNotificationPermissionDialog(
                NotificationPermissionOverlayState(
                    hasPendingRequest = true,
                    hasBlockingOverlay = true,
                ),
            ),
        ).isFalse()
        assertThat(
            shouldShowNotificationPermissionDialog(
                NotificationPermissionOverlayState(
                    hasPendingRequest = false,
                    hasBlockingOverlay = false,
                ),
            ),
        ).isFalse()
    }
}
