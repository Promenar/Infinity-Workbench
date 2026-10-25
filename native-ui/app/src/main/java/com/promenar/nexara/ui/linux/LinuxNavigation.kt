package com.promenar.nexara.ui.linux

import androidx.compose.runtime.staticCompositionLocalOf

/** 会话页内跳转到 Linux 相关页面的入口，绑定当前会话；不在导航宿主内（如截图预览）时为 null，入口随之隐藏。 */
class LinuxNavigation(
    val openEnvironment: () -> Unit,
    /** [messageId] 是含该调用步骤的助手消息，[toolCallId] 是协议层工具调用 id。 */
    val openCommandOutput: (messageId: String, toolCallId: String) -> Unit,
)

val LocalLinuxNavigation = staticCompositionLocalOf<LinuxNavigation?> { null }
