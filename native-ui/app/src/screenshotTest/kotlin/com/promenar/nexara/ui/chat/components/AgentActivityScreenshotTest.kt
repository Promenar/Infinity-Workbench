package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.schedule.ScheduleRunResult
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.ScheduledTask
import com.promenar.nexara.domain.trigger.SystemTriggerMessage
import com.promenar.nexara.domain.trigger.SystemTurnKind
import com.promenar.nexara.domain.jobs.BackgroundJobExitAction as ExitAction
import com.promenar.nexara.ui.chat.ApprovalCard
import com.promenar.nexara.ui.chat.ApprovalDisplayCall
import com.promenar.nexara.ui.chat.ApprovalPreview
import com.promenar.nexara.ui.chat.BackgroundRunPreview
import com.promenar.nexara.ui.chat.ScheduleApprovalPreview
import com.promenar.nexara.ui.chat.SystemTriggerRow
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val NOW = 1_792_400_000_000L

@Composable
private fun ActivityPreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

private fun previewJob(id: String, summary: String, state: BackgroundJobState, exit: Int? = null, runner: BackgroundJobRunner = BackgroundJobRunner.SHELL) = BackgroundJob(
    id = id,
    sessionId = "s",
    runner = runner,
    summary = summary,
    state = state,
    exitCode = exit,
    startedAtMillis = NOW - 192_000,
    endedAtMillis = if (state == BackgroundJobState.RUNNING) null else NOW - 12_000,
    maxRuntimeMinutes = 120,
    onExit = BackgroundJobExitAction.WAKE,
    logWorkspacePath = "jobs/$id.log",
    liveOutputKey = "job:$id",
)

private fun previewTask(id: String, name: String, trigger: ScheduleTrigger, enabled: Boolean = true, exactRequested: Boolean = false, exactEffective: Boolean = false) = ScheduledTask(
    id = id,
    sessionId = "s",
    name = name,
    prompt = "检查",
    trigger = trigger,
    exactRequested = exactRequested,
    exactEffective = exactEffective,
    enabled = enabled,
    nextFireAtMillis = NOW + 25 * 60_000,
    runCount = 3,
    maxRuns = if (id == "b") 10 else null,
    expiresAtMillis = null,
    lastFiredAtMillis = NOW - 5 * 60_000,
    lastResult = ScheduleRunResult.COMPLETED,
    createdAtMillis = NOW - 86_400_000,
)

private val previewJobs = listOf(
    previewJob("a", "./gradlew assembleRelease", BackgroundJobState.RUNNING),
    previewJob("b", "python3 crawl.py --pages 200", BackgroundJobState.EXITED, exit = 0, runner = BackgroundJobRunner.PYTHON),
    previewJob("c", "pkg upgrade -y", BackgroundJobState.TIMED_OUT, runner = BackgroundJobRunner.TERMUX),
)

@PreviewTest
@Preview(name = "活动标签与触发消息 浅中360", widthDp = 360, heightDp = 260, locale = "zh-rCN")
@Composable
fun agentActivityStripLightChinesePreview() = ActivityPreviewFrame(dark = false) {
    Column(Modifier.padding(vertical = NexaraSpacing.Large), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)) {
        AgentActivityStrip(
            summary = AgentActivitySummary(runningJobs = 2, latestFinishedJob = null, enabledSchedules = 3, nextFireAtMillis = NOW + 25 * 60_000),
            nowMillis = NOW,
            onOpenJobs = {},
            onOpenSchedules = {},
        )
        SystemTriggerRow(
            SystemTriggerMessage.Parsed(
                kind = SystemTurnKind.SCHEDULE,
                sourceId = "a",
                title = "检查 CI 构建",
                sequence = 3,
                catchUp = false,
                firedAt = OffsetDateTime.of(2026, 10, 23, 10, 30, 0, 0, ZoneOffset.ofHours(8)),
                body = "查看最新一次构建是否通过。",
            ),
        )
        SystemTriggerRow(
            SystemTriggerMessage.Parsed(
                kind = SystemTurnKind.JOB_EXIT,
                sourceId = "b",
                title = "python3 crawl.py --pages 200",
                sequence = null,
                catchUp = false,
                firedAt = OffsetDateTime.of(2026, 10, 23, 10, 42, 0, 0, ZoneOffset.ofHours(8)),
                body = "退出码 0",
            ),
        )
    }
}

@PreviewTest
@Preview(name = "后台任务面板 Play 深中360", widthDp = 360, heightDp = 420, locale = "zh-rCN")
@Composable
fun backgroundJobsPlayDarkChinesePreview() = ActivityPreviewFrame(dark = true) {
    BackgroundJobsContent(jobs = previewJobs, nowMillis = NOW, keepAliveAvailable = false, onStop = {}, onOpenLog = {})
}

@PreviewTest
@Preview(name = "后台任务面板 侧载 浅英360 大字体", widthDp = 360, heightDp = 480, locale = "en", fontScale = 1.3f)
@Composable
fun backgroundJobsSideloadLightEnglishPreview() = ActivityPreviewFrame(dark = false) {
    BackgroundJobsContent(jobs = previewJobs, nowMillis = NOW, keepAliveAvailable = true, onStop = {}, onOpenLog = {})
}

@PreviewTest
@Preview(name = "定时任务面板 Play 浅中360", widthDp = 360, heightDp = 420, locale = "zh-rCN")
@Composable
fun scheduledTasksPlayLightChinesePreview() = ActivityPreviewFrame(dark = false) {
    ScheduledTasksContent(
        tasks = listOf(
            previewTask("a", "检查 CI 构建", ScheduleTrigger.Interval(30)),
            previewTask("b", "工作日早报", ScheduleTrigger.Cron("0 9 * * 1-5", "Asia/Shanghai")),
            previewTask("c", "提醒续费", ScheduleTrigger.Once("2026-10-30T09:00", "Asia/Shanghai"), enabled = false),
        ),
        nowMillis = NOW,
        exactAlarmAvailable = false,
        onToggle = { _, _ -> },
        onDelete = {},
        onGrantExact = null,
    )
}

@PreviewTest
@Preview(name = "定时任务面板 侧载 深英360", widthDp = 360, heightDp = 360, locale = "en")
@Composable
fun scheduledTasksSideloadDarkEnglishPreview() = ActivityPreviewFrame(dark = true) {
    ScheduledTasksContent(
        tasks = listOf(
            previewTask("a", "Check CI build", ScheduleTrigger.Interval(120), exactRequested = true, exactEffective = true),
            previewTask("b", "Poll deployment", ScheduleTrigger.Interval(5), exactRequested = true, exactEffective = false),
        ),
        nowMillis = NOW,
        exactAlarmAvailable = true,
        onToggle = { _, _ -> },
        onDelete = {},
        onGrantExact = {},
    )
}

@PreviewTest
@Preview(name = "定时与后台命令审批 侧载 浅中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun scheduleApprovalSideloadLightChinesePreview() = ActivityPreviewFrame(dark = false) {
    ApprovalCard(
        toolName = "schedule_create",
        description = "每半小时检查一次构建状态，失败时提醒你。",
        calls = listOf(
            ApprovalDisplayCall(
                name = "schedule_create",
                argumentsSummary = "{}",
                riskLabel = "定时执行",
                schedule = ScheduleApprovalPreview(
                    name = "检查 CI 构建",
                    prompt = "查看最新一次构建是否通过；失败时总结错误并给出修复建议。",
                    trigger = ScheduleTrigger.Interval(30),
                    upcoming = listOf(NOW + 30 * 60_000, NOW + 60 * 60_000, NOW + 90 * 60_000),
                    nowMillis = NOW,
                    maxRuns = 10,
                    exactRequested = true,
                    exactEffective = true,
                    exactAlarmAvailable = true,
                    invalid = false,
                ),
            ),
            ApprovalDisplayCall(
                name = "shell_exec",
                argumentsSummary = "{}",
                riskLabel = "执行命令",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", "./gradlew assembleRelease", commandRunsInWorkspace = true),
                highRisk = true,
                background = BackgroundRunPreview(120, ExitAction.WAKE, keepAliveAvailable = true),
            ),
        ),
        onApproveAll = {},
    )
}

@PreviewTest
@Preview(name = "定时审批 Play 深英360", widthDp = 360, heightDp = 520, locale = "en")
@Composable
fun scheduleApprovalPlayDarkEnglishPreview() = ActivityPreviewFrame(dark = true) {
    ApprovalCard(
        toolName = "schedule_create",
        description = "",
        calls = listOf(
            ApprovalDisplayCall(
                name = "schedule_create",
                argumentsSummary = "{}",
                riskLabel = "Scheduled run",
                schedule = ScheduleApprovalPreview(
                    name = "Poll deployment",
                    prompt = "Check whether the deployment finished.",
                    trigger = ScheduleTrigger.Interval(5),
                    upcoming = listOf(NOW + 5 * 60_000, NOW + 10 * 60_000, NOW + 15 * 60_000),
                    nowMillis = NOW,
                    maxRuns = null,
                    exactRequested = true,
                    exactEffective = false,
                    exactAlarmAvailable = false,
                    invalid = false,
                ),
                background = null,
            ),
            ApprovalDisplayCall(
                name = "termux_exec",
                argumentsSummary = "{\"command\":\"pkg upgrade -y\",\"background\":true}",
                riskLabel = "Process",
                background = BackgroundRunPreview(45, ExitAction.NOTIFY, keepAliveAvailable = false),
            ),
        ),
    )
}
