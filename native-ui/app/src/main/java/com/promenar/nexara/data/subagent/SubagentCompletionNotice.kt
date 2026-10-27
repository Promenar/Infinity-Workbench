package com.promenar.nexara.data.subagent

import kotlinx.serialization.Serializable

/** 一次执行的持久通知正文；恢复任务不会覆盖尚未送达的旧结论。 */
@Serializable
data class SubagentCompletionNotice(
    val sequence: Int,
    val status: SubagentStatus,
    val body: String,
    val endedAt: Long,
    val order: Long = endedAt,
)
