package com.promenar.nexara.data.model

/** 生成摘要时的输入快照，用于在持久化前检查历史是否已变化。 */
data class ContextCompactionSource(
    val previousSummary: String?,
    val messages: List<Message>,
)
