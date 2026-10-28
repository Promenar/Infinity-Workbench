package com.promenar.infinity.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话顶栏左键。为 null 时是返回键；会话嵌在首页会话列（内屏三列）时由首页换成展开按钮。
 */
val LocalChatNavigationIcon = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
