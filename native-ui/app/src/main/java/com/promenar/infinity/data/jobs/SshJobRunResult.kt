package com.promenar.infinity.data.jobs

import com.promenar.infinity.domain.tool.CommandToolResult

/** 同步结果与已交给应用持有的任务二选一。 */
data class SshJobRunResult(
    val command: CommandToolResult? = null,
    val job: BackgroundJobRecord? = null,
    val yieldedAfterSeconds: Int? = null,
)
