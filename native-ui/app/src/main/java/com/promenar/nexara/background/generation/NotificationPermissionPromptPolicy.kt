package com.promenar.nexara.background.generation

data class NotificationPermissionPromptState(
    val sdkInt: Int,
    val granted: Boolean,
    val alreadyAsked: Boolean,
)

fun shouldExplainNotificationPermission(state: NotificationPermissionPromptState): Boolean =
    state.sdkInt >= 33 && !state.granted && !state.alreadyAsked

data class NotificationPermissionOverlayState(
    val hasPendingRequest: Boolean,
    val hasBlockingOverlay: Boolean,
)

fun shouldShowNotificationPermissionDialog(state: NotificationPermissionOverlayState): Boolean =
    state.hasPendingRequest && !state.hasBlockingOverlay

const val GENERATION_NOTIFICATION_PERMISSION_PREFS = "generation_notification_permission"
const val GENERATION_NOTIFICATION_PERMISSION_ASKED = "post_notifications_asked"
