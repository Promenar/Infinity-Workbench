package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.TouchLockUnlock

// 触摸锁遮罩：纯黑底不随主题变化，浅色主题下同样是暗底。
@PreviewTest
@Preview(name = "触摸锁 长按 执行中 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Preview(name = "触摸锁 长按 执行中 浅英411 大字", widthDp = 411, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun touchLockHoldRunningPreview() = TouchLockPreview(
    TouchLockUiState(TouchLockPhase.RUNNING, "验证任务面板交互与视觉回归", meta = "1/2 · 4 分", progress = 0.5f),
    TouchLockUnlock.HOLD,
)

@PreviewTest
@Preview(name = "触摸锁 滑动 待批准 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun touchLockSlideApprovalPreview() = TouchLockPreview(
    TouchLockUiState(TouchLockPhase.APPROVAL, "运行命令 git push"),
    TouchLockUnlock.SLIDE,
)

@PreviewTest
@Preview(name = "触摸锁 滑动 从右到左 英360", widthDp = 360, heightDp = 780, locale = "en")
@Composable
fun touchLockSlideRtlPreview() = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    TouchLockPreview(TouchLockUiState(TouchLockPhase.RUNNING, "Search the web", meta = "3 min"), TouchLockUnlock.SLIDE)
}

@PreviewTest
@Preview(name = "触摸锁 长按 已完成 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun touchLockDonePreview() = TouchLockPreview(TouchLockUiState(TouchLockPhase.DONE, "", meta = "2/2"), TouchLockUnlock.HOLD)

@Composable
private fun TouchLockPreview(state: TouchLockUiState, unlock: TouchLockUnlock) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        TouchLockContent(state, unlock, onUnlock = {}, modifier = Modifier.fillMaxSize())
    }
}
