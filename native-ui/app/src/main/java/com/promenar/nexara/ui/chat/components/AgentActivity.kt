package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.domain.jobs.BackgroundJob
import com.promenar.nexara.domain.jobs.BackgroundJobRunner
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.schedule.ScheduleRunResult
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.schedule.ScheduledTask
import com.promenar.nexara.ui.common.ChannelLimitChip
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedVisibility
import com.promenar.nexara.ui.theme.NexaraMotion

// ─────────────────────────────── 后台任务面板 ───────────────────────────────

/**
 * 本会话的后台任务。Play 版在标题旁用一枚标签说明“仅前台运行”，不在列表里重复解释；
 * [liveOutput] 由宿主按任务的实时输出键渲染终端区。
 */
@Composable
internal fun BackgroundJobsContent(
    jobs: List<BackgroundJob>,
    nowMillis: Long,
    keepAliveAvailable: Boolean,
    onStop: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    liveOutput: @Composable (BackgroundJob) -> Unit = {},
) {
    val groups = remember(jobs) { backgroundJobGroups(jobs) }
    var expandedId by rememberSaveable { mutableStateOf(groups.running.firstOrNull()?.id) }
    val toggle = { id: String -> expandedId = if (expandedId == id) null else id }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(UiTags.BACKGROUND_JOBS_SHEET),
        contentPadding = PaddingValues(start = NexaraSpacing.Medium, end = NexaraSpacing.Medium, bottom = NexaraSpacing.Large),
    ) {
        if (!keepAliveAvailable) {
            item(key = "limit") {
                Row(Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small), horizontalArrangement = Arrangement.End) {
                    ChannelLimitChip(
                        label = stringResource(R.string.agent_jobs_limit_label),
                        title = stringResource(R.string.agent_jobs_limit_title),
                        body = stringResource(R.string.agent_jobs_limit_body),
                        icon = Icons.Rounded.Visibility,
                    )
                }
            }
        }
        if (jobs.isEmpty()) {
            item(key = "empty") { EmptyHint(Icons.Rounded.Terminal, stringResource(R.string.agent_jobs_empty)) }
        }
        if (groups.running.isNotEmpty()) {
            item(key = "running_label") { JobGroupLabel(stringResource(R.string.agent_jobs_group_running, groups.running.size)) }
            items(groups.running, key = { it.id }) { job ->
                RunningJobCard(
                    job = job,
                    nowMillis = nowMillis,
                    expanded = expandedId == job.id,
                    onToggle = { toggle(job.id) },
                    onStop = { onStop(job.id) },
                    onOpenLog = job.logWorkspacePath?.let { path -> { onOpenLog(path) } },
                    liveOutput = { liveOutput(job) },
                    modifier = Modifier.padding(bottom = NexaraSpacing.Small),
                )
            }
        }
        if (groups.finished.isNotEmpty()) {
            item(key = "finished_label") { JobGroupLabel(stringResource(R.string.agent_jobs_group_finished, groups.finished.size)) }
            item(key = "finished") {
                Surface(shape = JobGroupShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        groups.finished.forEachIndexed { index, job ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            FinishedJobRow(
                                job = job,
                                nowMillis = nowMillis,
                                expanded = expandedId == job.id,
                                onToggle = { toggle(job.id) },
                                onOpenLog = job.logWorkspacePath?.let { path -> { onOpenLog(path) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobGroupLabel(text: String) {
    Text(
        text,
        style = NexaraTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = NexaraSpacing.XSmall, top = NexaraSpacing.Medium, bottom = NexaraSpacing.Small),
    )
}

/** 运行中的任务：命令、执行环境与已运行时长，右侧停止；展开时下面是终端风格的实时输出与查看日志。 */
@Composable
private fun RunningJobCard(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: (() -> Unit)?,
    liveOutput: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JobGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Small, top = NexaraSpacing.Small, bottom = NexaraSpacing.Medium)
                .testTag(UiTags.backgroundJob(job.id)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(NexaraSpacing.Medium))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = job.summary,
                        style = NexaraTypography.bodyMedium.copy(fontFamily = NexaraMonospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallTag(jobRunnerName(job), MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text(
                            jobStateText(job, nowMillis),
                            style = NexaraTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                OutlinedIconButton(
                    onClick = onStop,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag(UiTags.backgroundJobStop(job.id)),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.agent_jobs_stop), modifier = Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(visible = expanded, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                Column(Modifier.padding(top = NexaraSpacing.Small, end = NexaraSpacing.XSmall)) {
                    liveOutput()
                    if (onOpenLog != null) {
                        TextButton(
                            onClick = onOpenLog,
                            modifier = Modifier.align(Alignment.End).heightIn(min = NexaraSpacing.MinimumTouchTarget),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(NexaraSpacing.RowIconSize))
                            Text(stringResource(R.string.agent_jobs_open_log), modifier = Modifier.padding(start = NexaraSpacing.Small))
                        }
                    }
                }
            }
        }
    }
}

/** 已结束的任务：状态图标、命令与用时；非零退出码换成红色“退出 N”标签，展开后给出完整说明与查看日志。 */
@Composable
private fun FinishedJobRow(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLog: (() -> Unit)?,
) {
    val failedExit = job.state == BackgroundJobState.EXITED && job.exitCode != null && job.exitCode != 0
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Medium, top = 10.dp, bottom = 10.dp)
            .testTag(UiTags.backgroundJob(job.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStateIcon(job, Modifier.size(18.dp))
            Spacer(Modifier.width(NexaraSpacing.Medium))
            Text(
                text = job.summary,
                style = NexaraTypography.bodyMedium.copy(fontFamily = NexaraMonospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NexaraSpacing.Small))
            if (failedExit) {
                SmallTag(
                    stringResource(R.string.agent_jobs_exit_badge, job.exitCode ?: -1),
                    MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                Text(
                    durationText(compactDuration((job.endedAtMillis ?: nowMillis) - job.startedAtMillis)),
                    style = NexaraTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
            Column(Modifier.padding(start = 18.dp + NexaraSpacing.Medium, top = NexaraSpacing.XSmall)) {
                Text(jobMeta(job, nowMillis), style = NexaraTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenLog != null) {
                    TextButton(onClick = onOpenLog, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(NexaraSpacing.RowIconSize))
                        Text(stringResource(R.string.agent_jobs_open_log), modifier = Modifier.padding(start = NexaraSpacing.Small))
                    }
                }
            }
        }
    }
}

private val JobGroupShape = RoundedCornerShape(14.dp)

/** [sessionTitle] 仅在跨会话列表中给出，追加在说明行末尾。 */
@Composable
internal fun BackgroundJobRow(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: (() -> Unit)?,
    liveOutput: @Composable () -> Unit,
    sessionTitle: String? = null,
) {
    val running = !job.state.isTerminal
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = NexaraSpacing.Large, end = NexaraSpacing.Small, top = NexaraSpacing.Small, bottom = NexaraSpacing.Small)
            .testTag(UiTags.backgroundJob(job.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else JobStateIcon(job, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(NexaraSpacing.Medium))
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.summary,
                    style = NexaraTypography.bodyMedium.copy(fontFamily = NexaraMonospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(jobMeta(job, nowMillis), sessionTitle).joinToString(" · "),
                    style = NexaraTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (running) {
                FilledTonalIconButton(onClick = onStop, modifier = Modifier.testTag(UiTags.backgroundJobStop(job.id))) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.agent_jobs_stop), modifier = Modifier.size(18.dp))
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 24.dp + NexaraSpacing.Medium, top = NexaraSpacing.Small, end = NexaraSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            ) {
                if (running) liveOutput()
                if (onOpenLog != null) {
                    TextButton(onClick = onOpenLog, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(NexaraSpacing.RowIconSize))
                        Text(stringResource(R.string.agent_jobs_open_log), modifier = Modifier.padding(start = NexaraSpacing.Small))
                    }
                }
            }
        }
    }
}

@Composable
private fun JobStateIcon(job: BackgroundJob, modifier: Modifier) {
    val ok = job.state == BackgroundJobState.EXITED && job.exitCode == 0
    val (icon, tint) = when {
        ok -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        job.state == BackgroundJobState.STOPPED || job.state == BackgroundJobState.INTERRUPTED ->
            Icons.Rounded.PauseCircle to MaterialTheme.colorScheme.onSurfaceVariant
        job.state == BackgroundJobState.TIMED_OUT -> Icons.Rounded.HourglassBottom to MaterialTheme.colorScheme.error
        job.state == BackgroundJobState.RUNNING -> Icons.Rounded.Terminal to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
private fun jobMeta(job: BackgroundJob, nowMillis: Long): String = "${jobRunnerName(job)} · ${jobStateText(job, nowMillis)}"

@Composable
private fun jobRunnerName(job: BackgroundJob): String = stringResource(
    when (job.runner) {
        BackgroundJobRunner.SHELL -> R.string.agent_jobs_runner_shell
        BackgroundJobRunner.LINUX -> R.string.linux_runner_name
        BackgroundJobRunner.PYTHON -> R.string.agent_jobs_runner_python
        BackgroundJobRunner.TERMUX -> R.string.agent_jobs_runner_termux
    },
)

@Composable
private fun jobStateText(job: BackgroundJob, nowMillis: Long): String {
    val duration = durationText(compactDuration((job.endedAtMillis ?: nowMillis) - job.startedAtMillis))
    return when (job.state) {
        BackgroundJobState.RUNNING -> stringResource(R.string.agent_jobs_state_running, duration)
        BackgroundJobState.EXITED -> stringResource(R.string.agent_jobs_state_exited, job.exitCode ?: -1, duration)
        BackgroundJobState.STOPPED -> stringResource(R.string.agent_jobs_state_stopped, duration)
        BackgroundJobState.TIMED_OUT -> stringResource(R.string.agent_jobs_state_timed_out, duration)
        BackgroundJobState.INTERRUPTED -> stringResource(R.string.agent_jobs_state_interrupted)
        BackgroundJobState.FAILED -> stringResource(R.string.agent_jobs_state_failed)
    }
}

@Composable
private fun durationText(duration: CompactDuration): String = when {
    duration.hours > 0 -> stringResource(R.string.agent_duration_hm, duration.hours, duration.minutes)
    duration.minutes > 0 -> stringResource(R.string.agent_duration_ms, duration.minutes, duration.seconds)
    else -> stringResource(R.string.agent_duration_s, duration.seconds)
}

// ─────────────────────────────── 定时任务面板 ───────────────────────────────

/**
 * 本会话的定时任务：名称、触发规则、下次触发与最近结果，开关暂停，删除前确认。
 * [exactAlarmAvailable] 为 false（Play）时标题旁显示“系统择时”标签；侧载版缺少精确闹钟授权时，
 * 受影响的条目各自显示一枚可点的授权标签。
 */
@Composable
internal fun ScheduledTasksContent(
    tasks: List<ScheduledTask>,
    nowMillis: Long,
    exactAlarmAvailable: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onGrantExact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    LazyColumn(modifier = modifier.fillMaxWidth().testTag(UiTags.SCHEDULED_TASKS_SHEET)) {
        item(key = "header") {
            SheetHeader(title = stringResource(R.string.agent_schedules_title)) {
                if (!exactAlarmAvailable) {
                    ChannelLimitChip(
                        label = stringResource(R.string.agent_schedules_limit_label),
                        title = stringResource(R.string.agent_schedules_limit_title),
                        body = stringResource(R.string.agent_schedules_limit_body),
                        icon = Icons.Rounded.Schedule,
                    )
                }
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") { EmptyHint(Icons.Rounded.Schedule, stringResource(R.string.agent_schedules_empty)) }
        }
        items(tasks, key = { it.id }) { task ->
            ScheduledTaskRow(
                task = task,
                nowMillis = nowMillis,
                onToggle = { onToggle(task.id, it) },
                onDelete = { deleting = task },
                onGrantExact = onGrantExact?.takeIf { exactAlarmAvailable && task.exactRequested && !task.exactEffective },
            )
        }
    }
    deleting?.let { task ->
        NexaraConfirmDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.agent_schedules_delete_title),
            message = stringResource(R.string.agent_schedules_delete_body, task.name),
            confirmText = stringResource(R.string.agent_schedules_delete_confirm),
            cancelText = stringResource(android.R.string.cancel),
            onConfirm = { deleting = null; onDelete(task.id) },
            onCancel = { deleting = null },
            isDestructive = true,
        )
    }
}

@Composable
internal fun ScheduledTaskRow(
    task: ScheduledTask,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onGrantExact: (() -> Unit)?,
    sessionTitle: String? = null,
) {
    val stateText = stringResource(if (task.enabled) R.string.agent_schedules_enabled else R.string.agent_schedules_paused)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NexaraSpacing.Large, end = NexaraSpacing.Small, top = NexaraSpacing.Small, bottom = NexaraSpacing.Small)
            .testTag(UiTags.scheduledTask(task.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(task.name, style = NexaraTypography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(triggerText(task.trigger), sessionTitle).joinToString(" · "),
                style = NexaraTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scheduleStatus(task, nowMillis),
                    style = NexaraTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (task.exactEffective) SmallTag(stringResource(R.string.agent_schedules_exact), MaterialTheme.colorScheme.secondaryContainer)
            }
            // 授权入口单独一行，不挤占状态文字。
            if (onGrantExact != null) {
                Row(Modifier.padding(top = 2.dp)) {
                    Surface(
                        onClick = onGrantExact,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AlarmOff, contentDescription = null, modifier = Modifier.size(12.dp))
                            Text(stringResource(R.string.agent_schedules_grant_exact), style = NexaraTypography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
        Switch(
            checked = task.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier
                .semantics { stateDescription = stateText }
                .testTag(UiTags.scheduledTaskToggle(task.id)),
        )
        IconButton(onClick = onDelete, modifier = Modifier.testTag(UiTags.scheduledTaskDelete(task.id))) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.agent_schedules_delete_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun triggerText(trigger: ScheduleTrigger): String = when (trigger) {
    is ScheduleTrigger.Once -> {
        val at = runCatching { LocalDateTime.parse(trigger.localDateTime) }.getOrNull()
        stringResource(
            R.string.agent_schedules_trigger_once,
            at?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) ?: trigger.localDateTime,
        )
    }
    is ScheduleTrigger.Interval -> when (val unit = intervalUnit(trigger.everyMinutes)) {
        is IntervalUnit.Minutes -> pluralStringResource(R.plurals.agent_schedules_every_minutes, unit.value, unit.value)
        is IntervalUnit.Hours -> pluralStringResource(R.plurals.agent_schedules_every_hours, unit.value, unit.value)
        is IntervalUnit.Days -> pluralStringResource(R.plurals.agent_schedules_every_days, unit.value, unit.value)
    }
    is ScheduleTrigger.Cron -> stringResource(R.string.agent_schedules_trigger_cron, trigger.expression)
}

@Composable
private fun scheduleStatus(task: ScheduledTask, nowMillis: Long): String {
    val next = task.nextFireAtMillis?.takeIf { task.enabled }?.let {
        if (it <= nowMillis) stringResource(R.string.agent_schedules_due)
        else stringResource(R.string.agent_schedules_next, fireTimeLabel(it, nowMillis))
    }
    val last = task.lastResult?.let {
        stringResource(
            when (it) {
                ScheduleRunResult.COMPLETED -> R.string.agent_schedules_last_completed
                ScheduleRunResult.NEEDS_USER -> R.string.agent_schedules_last_needs_user
                ScheduleRunResult.SKIPPED_BUSY -> R.string.agent_schedules_last_skipped_busy
                ScheduleRunResult.SKIPPED_OTHER -> R.string.agent_schedules_last_skipped
                ScheduleRunResult.FAILED -> R.string.agent_schedules_last_failed
            },
        )
    }
    val runs = task.maxRuns?.let { stringResource(R.string.agent_schedules_runs_of, task.runCount, it) }
    return listOfNotNull(next ?: if (!task.enabled) stringResource(R.string.agent_schedules_paused) else null, last, runs).joinToString(" · ")
}

@Composable
internal fun fireTimeLabel(fireAtMillis: Long, nowMillis: Long): String {
    val zoned = Instant.ofEpochMilli(fireAtMillis).atZone(ZoneId.systemDefault())
    val time = zoned.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return when (fireDay(fireAtMillis, nowMillis)) {
        FireDay.TODAY -> time
        FireDay.TOMORROW -> stringResource(R.string.agent_schedules_tomorrow, time)
        FireDay.LATER -> zoned.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}

// ─────────────────────────────── 共用片段 ───────────────────────────────

@Composable
private fun SheetHeader(title: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Text(title, style = NexaraTypography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
        trailing()
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Text(text, style = NexaraTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallTag(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = CircleShape, color = color, contentColor = contentColor) {
        Text(text, style = NexaraTypography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
