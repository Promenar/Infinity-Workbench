package com.promenar.nexara.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest

private val applicationStreamAnimationSettings = StreamAnimationSettings(CoroutineScope(SupervisorJob() + Dispatchers.IO))

@Composable
internal fun rememberSmoothStreamContent(
    content: String,
    isStreaming: Boolean,
    animationSettings: @Composable () -> Boolean? = { rememberStreamAnimationsEnabled() },
): StreamRevealFrame {
    var revealActive by remember { mutableStateOf(isStreaming) }
    if (isStreaming) revealActive = true
    // 历史正文与已排空正文直接返回，既不创建放出器，也不读取动画设置或拆分字形。
    if (!revealActive) return StreamRevealFrame(content, 0L)
    val animationsEnabled = animationSettings()
    val frame = rememberSmoothStreamingContent(content, animationsEnabled, isStreaming)
    if (!isStreaming && frame.text == content && !frame.hasFadingText) {
        SideEffect { revealActive = false }
    }
    return frame
}

@Composable
internal fun rememberSmoothStreamingContent(
    content: String,
    animationsEnabled: Boolean?,
    isStreaming: Boolean = true,
): StreamRevealFrame {
    val revealer = remember { StreamRevealer(initialText = content) }
    var frame by remember { mutableStateOf(StreamRevealFrame(content, 0L)) }
    val currentInput = rememberUpdatedState(Triple(content, isStreaming, animationsEnabled))

    LaunchedEffect(revealer) {
        snapshotFlow { currentInput.value }.collectLatest { (target, streaming, animate) ->
            if (animate == null && streaming) return@collectLatest
            withFrameNanos { now ->
                frame = revealer.updateTarget(target, streaming, now, animationsEnabled = animate == true)
            }
            while (revealer.hasPendingText || frame.hasFadingText) {
                withFrameNanos { now -> frame = revealer.advanceFrame(now) }
            }
        }
    }
    return if (animationsEnabled == false || animationsEnabled == null && !isStreaming) {
        StreamRevealFrame(content, frame.nowNanos)
    } else frame
}

@Composable
internal fun rememberStreamAnimationsEnabled(): Boolean? {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state = remember(resolver) { applicationStreamAnimationSettings.state(resolver) }
    val enabled by state.collectAsState()
    return enabled
}
