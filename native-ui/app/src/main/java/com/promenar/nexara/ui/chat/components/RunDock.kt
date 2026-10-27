package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

/**
 * 输入浮岛顶部的运行坞：一行显示当前在做什么，右侧是待批准、后台任务与状态面板的入口，
 * 点按任意处打开运行详情。下方 2dp 进度线兼作与输入区的分隔；没有计划时画普通分隔线。
 */
@Composable
internal fun RunDockRow(
    state: RunDockUiState,
    onOpen: (RunDetailTab?) -> Unit,
    onJumpToApproval: () -> Unit,
    modifier: Modifier = Modifier,
    /** 生成中显示锁按钮，点按锁定触摸；为 null 时不显示。 */
    onLockTouch: (() -> Unit)? = null,
) {
    val label = runDockLabelText(state.label)
    val meta = runDockMetaText(state)
    val openLabel = stringResource(R.string.run_dock_open)
    val progress by animateFloatAsState(
        targetValue = state.progress ?: 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "run_dock_progress",
    )
    Column(modifier.fillMaxWidth().testTag(UiTags.CHAT_RUN_DOCK)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(runDockDefaultTab(state.label)) }
                    .padding(start = 14.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOf(label, meta).filter(String::isNotBlank).joinToString("，")
                    }
                    .testTag(UiTags.CHAT_RUN_DOCK_MAIN),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunDockDot(state.tone)
                // 标签与进度同一行文字：窄屏大字号时先截掉尾部的进度与用时，保住当前在做什么。
                val metaStyle = MaterialTheme.typography.labelMedium
                val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = buildAnnotatedString {
                        append(label)
                        if (meta.isNotBlank()) {
                            append("\u2002")
                            withStyle(SpanStyle(fontSize = metaStyle.fontSize, color = metaColor, fontFeatureSettings = "tnum")) { append(meta) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.pendingApprovals > 0) {
                RunDockChip(
                    onClick = onJumpToApproval,
                    label = stringResource(R.string.run_dock_jump_approval_cd, state.pendingApprovals),
                    container = MaterialTheme.nexaraDomainColors.warningContainer,
                    content = MaterialTheme.nexaraDomainColors.onWarningContainer,
                    tag = UiTags.CHAT_RUN_DOCK_APPROVAL,
                ) {
                    Text(
                        pluralStringResource(R.plurals.run_dock_pending_approvals, state.pendingApprovals, state.pendingApprovals),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            } else {
                if (state.runningJobs > 0 || state.enabledSchedules > 0) {
                    val jobsLabel = when {
                        state.runningJobs > 0 -> pluralStringResource(R.plurals.agent_jobs_running, state.runningJobs, state.runningJobs)
                        else -> pluralStringResource(R.plurals.agent_schedules_count, state.enabledSchedules, state.enabledSchedules)
                    }
                    RunDockChip(onClick = { onOpen(RunDetailTab.BACKGROUND) }, label = jobsLabel, tag = UiTags.CHAT_RUN_DOCK_JOBS) {
                        if (state.runningJobs > 0) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text("${state.runningJobs}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        } else {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("${state.enabledSchedules}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                }
                if (state.hasStateBoard && state.label !is RunDockLabel.Board) {
                    RunDockChip(
                        onClick = { onOpen(RunDetailTab.STATE) },
                        label = stringResource(R.string.state_open),
                        tag = UiTags.CHAT_RUN_DOCK_STATE,
                        square = true,
                    ) {
                        Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                }
            }
            if (onLockTouch != null) com.promenar.nexara.ui.chat.TouchLockButton(onLock = onLockTouch)
            IconButton(onClick = { onOpen(runDockDefaultTab(state.label)) }, modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget)) {
                Icon(
                    Icons.Rounded.ExpandLess,
                    contentDescription = openLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (state.progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Medium).height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = NexaraSpacing.Medium),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun RunDockChip(
    onClick: () -> Unit,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    body: @Composable () -> Unit,
) {
    // 外层 48dp 触控区，内层 32dp 可见胶囊。
    Box(
        modifier = modifier
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .padding(horizontal = if (square) 8.5.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) { body() }
        }
    }
}

@Composable
internal fun RunDockDot(tone: RunDockTone, modifier: Modifier = Modifier) {
    when (tone) {
        RunDockTone.DONE -> Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(16.dp),
        )
        RunDockTone.RUNNING -> TaskStatusDot(pulsing = true, color = MaterialTheme.colorScheme.primary, modifier = modifier)
        RunDockTone.WAITING -> TaskStatusDot(pulsing = true, color = MaterialTheme.nexaraDomainColors.warning, modifier = modifier)
        RunDockTone.PAUSED -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.tertiary, modifier = modifier)
        RunDockTone.IDLE -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.outline, modifier = modifier)
    }
}

@Composable
internal fun runDockLabelText(label: RunDockLabel): String = when (label) {
    is RunDockLabel.Step -> label.title
    RunDockLabel.Running -> stringResource(R.string.chat_progress_running)
    RunDockLabel.WaitingApproval -> stringResource(R.string.run_dock_waiting_approval)
    RunDockLabel.WaitingAnswer -> stringResource(R.string.run_dock_waiting_answer)
    RunDockLabel.PlanCompleted -> stringResource(R.string.run_dock_plan_completed)
    is RunDockLabel.Paused -> if (label.step.isBlank()) stringResource(R.string.run_dock_paused)
        else stringResource(R.string.run_dock_paused_step, label.step)
    RunDockLabel.BackgroundOnly -> stringResource(R.string.run_dock_background)
    is RunDockLabel.Board -> label.title
}

@Composable
internal fun runDockMetaText(state: RunDockUiState): String = buildList {
    if (state.planTotal != null) add("${state.planDone ?: 0}/${state.planTotal}")
    state.elapsedMinutes?.let { add(stringResource(R.string.run_dock_elapsed_minutes, it)) }
}.joinToString(" · ")
