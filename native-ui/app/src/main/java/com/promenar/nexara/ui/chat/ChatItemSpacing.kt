package com.promenar.nexara.ui.chat

internal enum class ChatItemKind { USER, ASSISTANT }

internal data class ChatItemSpacing(val beforeDp: Int, val afterDp: Int)

internal fun chatItemSpacing(
    previous: ChatItemKind?,
    current: ChatItemKind,
    isLast: Boolean = false,
): ChatItemSpacing = ChatItemSpacing(
    beforeDp = when {
        previous == null -> 12
        previous == ChatItemKind.USER && current == ChatItemKind.ASSISTANT -> 20
        previous == ChatItemKind.ASSISTANT && current == ChatItemKind.USER -> 28
        else -> 0
    },
    afterDp = if (isLast) 16 else 0,
)
