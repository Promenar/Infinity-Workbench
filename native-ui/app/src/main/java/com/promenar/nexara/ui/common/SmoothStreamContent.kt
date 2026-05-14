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
    BALANCED(6000, "平衡"),   //  同上——AI 应用不人为延迟
    SMOOTH(60, "平滑")       //  1 字符/帧，打字机效果
}

@Composable
fun rememberSmoothStreamContent(
    content: String,
    isStreaming: Boolean,
    cps: Int = StreamSpeed.BALANCED.cps
): String {
    var displayed by remember { mutableStateOf("") }
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
            if (displayed.length < target.length) {
                // 如果内容发生非追加式巨变（如新消息），重置
                if (displayed.isNotEmpty() && !target.startsWith(displayed)) {
                    displayed = ""
                }
                
                val currentPos = displayed.length
                // 即使在生成中，如果落后太多也应该加速追赶
                val speedFactor = if (target.length - currentPos > 100) 3 else 1
                val effectiveCps = cps * speedFactor
                val charsPerFrame = (effectiveCps / 60).coerceAtLeast(1)
                
                val nextPos = (currentPos + charsPerFrame).coerceAtMost(target.length)
                displayed = target.substring(0, nextPos)
            } else if (displayed.length > target.length) {
                // 处理极少数情况下的截断
                displayed = target
            }
            
            delay(frameDelay)
        }
        
        // 结束生成时的最终同步
        displayed = contentRef.value
    }

    // 对于非生成状态（历史消息），直接同步确保渲染正确
    if (!isStreaming && displayed != content) {
        displayed = content
    }

    return displayed
}
