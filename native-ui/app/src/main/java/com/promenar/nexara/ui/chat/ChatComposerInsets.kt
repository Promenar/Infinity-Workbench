package com.promenar.nexara.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

internal data class ChatComposerInsets(
    /** 静止时列表底部内边距；末尾留白条目再隔开 16dp。 */
    val contentBottom: Dp,
    /** 自动跟随时目标条目底边距视口底部的距离，即在输入区上方留出 32dp。 */
    val streamingOverlap: Dp,
    val fabBottom: Dp,
)

/** 生成中列表底部额外的内边距，使 [ChatComposerInsets.streamingOverlap] 在滚动到底时也能达到。 */
internal val ChatGeneratingExtraBottom: Dp = NexaraSpacing.Large

internal fun chatComposerInsets(composerHeight: Dp): ChatComposerInsets {
    val measuredHeight = if (composerHeight > 0.dp) composerHeight else 96.dp
    return ChatComposerInsets(
        contentBottom = measuredHeight,
        streamingOverlap = measuredHeight + NexaraSpacing.XXLarge,
        fabBottom = measuredHeight + NexaraSpacing.Large,
    )
}
