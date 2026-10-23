package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobController
import com.promenar.nexara.domain.schedule.ScheduledTask
import com.promenar.nexara.domain.schedule.ScheduledTaskController
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSheetHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 聊天页的后台活动入口；由 ChatRoute 按当前会话提供，没有提供时不显示任何内容。 */
internal class AgentActivityAccess(
    val sessionId: String,
    val jobs: BackgroundJobController?,
    val schedules: ScheduledTaskController?,
    val keepAliveAvailable: Boolean,
    val exactAlarmAvailable: Boolean,
    val onOpenLog: (String) -> Unit,
    /** 系统精确闹钟授权页；渠道不支持或已授权时为 null。 */
    val onGrantExact: (() -> Unit)?,
    val liveOutput: @Composable (BackgroundJob) -> Unit = {},
)

internal val LocalAgentActivity = staticCompositionLocalOf<AgentActivityAccess?> { null }

/** 活动标签与两个面板。时长与“下次触发”每 30 秒且在下次触发时刻刷新，任务运行中每秒刷新运行时长。 */
@Composable
internal fun AgentActivityPanel(modifier: Modifier = Modifier) {
    val access = LocalAgentActivity.current ?: return
    val jobsFlow = remember(access.sessionId, access.jobs) { access.jobs?.observe(access.sessionId) ?: flowOf(emptyList()) }
    val schedulesFlow = remember(access.sessionId, access.schedules) { access.schedules?.observe(access.sessionId) ?: flowOf(emptyList()) }
    val jobs by jobsFlow.collectAsState(initial = emptyList<BackgroundJob>())
    val schedules by schedulesFlow.collectAsState(initial = emptyList<ScheduledTask>())
    var showJobs by remember { mutableStateOf(false) }
    var showSchedules by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = jobs.any { !it.state.isTerminal }
    val nextFire = schedules.mapNotNull { it.nextFireAtMillis?.takeIf { _ -> it.enabled } }.minOrNull()
    LaunchedEffect(anyRunning, showJobs, nextFire) {
        while (true) {
            now = System.currentTimeMillis()
            val base = if (anyRunning && showJobs) 1_000L else 30_000L
            delay(nextFire?.let { (it - now).takeIf { untilFire -> untilFire > 0 } }?.coerceAtMost(base) ?: base)
        }
    }
    val scope = rememberCoroutineScope()
    AgentActivityStrip(
        summary = summarizeAgentActivity(jobs, schedules, now),
        nowMillis = now,
        onOpenJobs = { showJobs = true },
        onOpenSchedules = { showSchedules = true },
        modifier = modifier,
    )
    NexaraBottomSheet(show = showJobs, onDismiss = { showJobs = false }, height = NexaraSheetHeight.Full, dragGesturesEnabled = false) {
        BackgroundJobsContent(
            jobs = jobs,
            nowMillis = now,
            keepAliveAvailable = access.keepAliveAvailable,
            onStop = { id -> scope.launch { access.jobs?.stop(id) } },
            onOpenLog = { path -> showJobs = false; access.onOpenLog(path) },
            modifier = Modifier.fillMaxSize(),
            liveOutput = access.liveOutput,
        )
    }
    NexaraBottomSheet(show = showSchedules, onDismiss = { showSchedules = false }, height = NexaraSheetHeight.Full, dragGesturesEnabled = false) {
        ScheduledTasksContent(
            tasks = schedules,
            nowMillis = now,
            exactAlarmAvailable = access.exactAlarmAvailable,
            onToggle = { id, enabled -> scope.launch { if (enabled) access.schedules?.resume(id) else access.schedules?.pause(id) } },
            onDelete = { id -> scope.launch { access.schedules?.delete(id) } },
            onGrantExact = access.onGrantExact,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
