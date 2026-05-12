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
        if (!isStreaming) {
            displayed = content
            return@LaunchedEffect
        }

        val target = content
        var pos = displayed.length

        if (pos > target.length) {
            pos = 0
            displayed = ""
        }

        val frameDelay = 1000L / 30
        val charsPerFrame = (cps / 30).coerceIn(1, 3)

        while (pos < target.length) {
            val end = (pos + charsPerFrame).coerceAtMost(target.length)
            pos = end
            displayed = target.substring(0, pos)
            delay(frameDelay)
        }

        displayed = content
    }

    return displayed
}
