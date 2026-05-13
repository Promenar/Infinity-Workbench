package com.promenar.nexara.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

enum class StreamSpeed(val cps: Int, val label: String) {
    FAST(55, "快速"),
    BALANCED(38, "平衡"),
    SMOOTH(25, "平滑")
}

@Composable
fun rememberSmoothStreamContent(
    content: String,
    isStreaming: Boolean,
    cps: Int = StreamSpeed.BALANCED.cps
): String {
    var displayed by remember { mutableStateOf("") }

    LaunchedEffect(content, isStreaming, cps) {
        val target = content
        var pos = displayed.length

        // If not streaming and we're starting from scratch (historical message), show immediately
        if (!isStreaming && (pos == 0 || pos >= target.length)) {
            displayed = target
            return@LaunchedEffect
        }

        // If content changed significantly (new message), reset
        if (pos > target.length) {
            pos = 0
            displayed = ""
        }

        val frameDelay = 1000L / 60 // Boost to 60fps for smoother ultra-fast streaming
        // If still streaming, use defined CPS. If finished but catching up, go 5x faster for extreme snappiness.
        val effectiveCps = if (isStreaming) cps else cps * 5
        val charsPerFrame = (effectiveCps / 60).coerceAtLeast(1)

        while (pos < target.length) {
            val end = (pos + charsPerFrame).coerceAtMost(target.length)
            pos = end
            displayed = target.substring(0, pos)
            delay(frameDelay)
        }

        displayed = target
    }

    return displayed
}
