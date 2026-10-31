package com.promenar.infinity.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.tool.ExecToolArguments
import com.promenar.infinity.ui.chat.components.LocalAgentActivity
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 本会话后台任务的显示名（任务 ID → 短标题，没有时为命令原文），供 job_* 调用说明它在操作哪个任务。 */
internal val LocalBackgroundJobLabels = compositionLocalOf<Map<String, String>> { emptyMap() }

/** 后台任务的显示名：优先智能体写的短标题，没有时从左往右显示命令原文。 */
internal fun BackgroundJob.displayLabel(): String = title?.takeIf(String::isNotBlank) ?: summary

internal enum class CommandStepKind { RUN, WAIT, STATUS, STOP, INPUT }

/**
 * 命令类调用在运行记录里的显示内容：[label] 为短标题、所操作任务的显示名，或无法识别任务时的短任务号；
 * [waitLimitSeconds] 只在 job_wait 时给出最长等待时间。
 */
internal data class CommandStepLabel(
    val kind: CommandStepKind,
    val label: String?,
    val jobId: String? = null,
    val waitLimitSeconds: Int? = null,
)

private val JOB_KINDS = mapOf(
    "job_wait" to CommandStepKind.WAIT,
    "job_status" to CommandStepKind.STATUS,
    "job_stop" to CommandStepKind.STOP,
    "job_input" to CommandStepKind.INPUT,
)

/** job_wait 未传 timeout_seconds 时的默认等待时间，与工具定义一致。 */
private const val DEFAULT_WAIT_SECONDS = 60

internal fun commandStepLabel(toolName: String?, toolArgs: String?, jobLabels: Map<String, String>): CommandStepLabel? {
    val args = toolArgs?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    JOB_KINDS[toolName]?.let { kind ->
        val jobId = (args["job_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        return CommandStepLabel(
            kind = kind,
            label = jobId?.let { jobLabels[it] },
            jobId = jobId,
            waitLimitSeconds = if (kind == CommandStepKind.WAIT) {
                (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_WAIT_SECONDS
            } else null,
        )
    }
    if (toolName !in setOf("shell_exec", "python_exec", "termux_exec", "ssh_exec")) return null
    return CommandStepLabel(CommandStepKind.RUN, ExecToolArguments.title(args))
}

/** 任务号的短形式：去掉 `job-` 前缀后取前 8 位，只在找不到任务记录时显示。 */
internal fun shortJobId(jobId: String): String = jobId.removePrefix("job-").take(8)

/** 订阅当前会话的后台任务，得到 [LocalBackgroundJobLabels] 的取值；没有后台活动入口时为空。 */
@Composable
internal fun rememberBackgroundJobLabels(): Map<String, String> {
    val access = LocalAgentActivity.current ?: return emptyMap()
    val flow = remember(access.sessionId, access.jobs) { access.jobs?.observe(access.sessionId) ?: flowOf(emptyList()) }
    val jobs by flow.collectAsState(initial = emptyList())
    return remember(jobs) { jobs.associate { it.id to it.displayLabel() } }
}
