package com.promenar.nexara.data.generation

data class ContextUsageEstimate(
    val systemTokens: Int,
    val summaryTokens: Int,
    val conversationTokens: Int,
    val ragTokens: Int,
    val foldedToolResults: Int,
    val modelContextTokens: Int,
) {
    val totalTokens: Int get() = (systemTokens.toLong() + summaryTokens + conversationTokens + ragTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
