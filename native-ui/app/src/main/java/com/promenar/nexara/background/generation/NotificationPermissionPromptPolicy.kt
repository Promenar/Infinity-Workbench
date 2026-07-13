package com.promenar.nexara.background.generation

data class NotificationPermissionPromptState(
    val sdkInt: Int,
    val granted: Boolean,
    val alreadyAsked: Boolean,
)

fun shouldExplainNotificationPermission(state: NotificationPermissionPromptState): Boolean =
    state.sdkInt >= 33 && !state.granted && !state.alreadyAsked

/**
 * 通知权限快照端口：把 Android Context 相关的权限读取与 SharedPreferences 状态收敛为纯数据，
 * 供 ChatViewModel 在启动后台可持续生成之前做可测试的时序判定。
 */
fun interface NotificationPermissionGateway {
    fun current(): NotificationPermissionPromptState?
}

/** 后台可持续生成的预判结果：在发送前决定是否需要先解释并取得系统权限。 */
enum class BackgroundGenerationPolicy { BACKGROUND_ALLOWED, FOREGROUND_ONLY, REQUIRES_PERMISSION }

/**
 * 发送后台可持续生成前的权限时序判定：
 * - null（无端口，旧路径）或低版本或已授权 -> 允许后台；
 * - 未授权且从未问过 -> 必须先解释并取得系统结果；
 * - 未授权且已问过（用户曾拒绝）-> 明确降级为仅前台生成。
 */
fun resolveBackgroundGenerationPolicy(
    snapshot: NotificationPermissionPromptState?,
): BackgroundGenerationPolicy = when {
    snapshot == null || snapshot.sdkInt < 33 || snapshot.granted ->
        BackgroundGenerationPolicy.BACKGROUND_ALLOWED
    shouldExplainNotificationPermission(snapshot) ->
        BackgroundGenerationPolicy.REQUIRES_PERMISSION
    else -> BackgroundGenerationPolicy.FOREGROUND_ONLY
}

data class NotificationPermissionOverlayState(
    val hasPendingRequest: Boolean,
    val hasBlockingOverlay: Boolean,
)

fun shouldShowNotificationPermissionDialog(state: NotificationPermissionOverlayState): Boolean =
    state.hasPendingRequest && !state.hasBlockingOverlay

const val GENERATION_NOTIFICATION_PERMISSION_PREFS = "generation_notification_permission"
const val GENERATION_NOTIFICATION_PERMISSION_ASKED = "post_notifications_asked"
