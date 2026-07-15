package com.promenar.nexara.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

internal data class ChatComposerInsets(
    val contentBottom: Dp,
    val streamingOverlap: Dp,
    val fabBottom: Dp,
)

internal fun chatComposerInsets(composerHeight: Dp): ChatComposerInsets {
    val measuredHeight = if (composerHeight > 0.dp) composerHeight else 96.dp
    return ChatComposerInsets(
        contentBottom = measuredHeight + NexaraSpacing.XLarge,
        streamingOverlap = measuredHeight + NexaraSpacing.Large,
        fabBottom = measuredHeight + NexaraSpacing.Large,
    )
}
