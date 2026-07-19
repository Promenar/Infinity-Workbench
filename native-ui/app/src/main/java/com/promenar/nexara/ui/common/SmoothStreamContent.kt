package com.promenar.nexara.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

enum class StreamSpeed(val cps: Int, val label: String) {
    FAST(6000, "快速"),      // ~100 字符/帧，接近实时
    BALANCED(6000, "平衡"),   // 普通 chunk 立即呈现，由绘制层负责短暂尾部淡入
    SMOOTH(60, "平滑")       //  1 字符/帧，打字机效果
}

internal fun reduceSmoothStreamContent(
    displayed: String,
    target: String,
    isStreaming: Boolean,
    cps: Int
): String {
    if (!isStreaming || displayed.length > target.length) {
        return target
    }
    if (displayed == target) {
        return displayed
    }

    val current = if (displayed.isNotEmpty() && !target.startsWith(displayed)) {
        ""
    } else {
        displayed
    }
    val speedFactor = if (target.length - current.length > 100) 3 else 1
    val charsPerFrame = (cps * speedFactor / 60).coerceAtLeast(1)
    val nextPosition = (current.length + charsPerFrame).coerceAtMost(target.length)
    return target.substring(0, nextPosition)
}

@Composable
fun rememberSmoothStreamContent(
    content: String,
    isStreaming: Boolean,
    cps: Int = StreamSpeed.BALANCED.cps
): String {
    var displayed by remember { mutableStateOf(content) }
    // 使用 rememberUpdatedState 保持对最新内容的引用，避免频繁重启 LaunchedEffect 导致卡顿
    val contentRef = rememberUpdatedState(content)

    LaunchedEffect(isStreaming, cps) {
        if (!isStreaming) {
            displayed = contentRef.value
            return@LaunchedEffect
        }

        val frameDelay = 16L // ~60fps
        
        while (isStreaming) {
            val target = contentRef.value
            displayed = reduceSmoothStreamContent(displayed, target, isStreaming = true, cps)
            
            delay(frameDelay)
        }
        
        // 结束生成时的最终同步
        displayed = reduceSmoothStreamContent(
            displayed,
            contentRef.value,
            isStreaming = false,
            cps
        )
    }

    // 对于非生成状态（历史消息），直接同步确保渲染正确
    if (!isStreaming && displayed != content) {
        displayed = reduceSmoothStreamContent(displayed, content, isStreaming = false, cps)
    }

    return displayed
}
