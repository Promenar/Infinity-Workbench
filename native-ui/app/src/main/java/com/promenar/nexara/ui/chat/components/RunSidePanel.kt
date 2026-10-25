package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.canvas.CanvasMetadata
import com.promenar.nexara.data.state.StateBoardSnapshot
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.chat.TaskPanelStep
import com.promenar.nexara.ui.chat.TaskPanelStepStatus
import com.promenar.nexara.ui.chat.TaskPanelUiState
import com.promenar.nexara.ui.chat.stateValueText
import com.promenar.nexara.ui.git.GitEntrySummary
import com.promenar.nexara.ui.git.gitSummaryLine
import com.promenar.nexara.ui.git.gitTrackingCount
import com.promenar.nexara.ui.testing.UiTags

/** 宽屏（折叠屏展开、平板）会话右侧的运行侧栏宽度。 */
internal val RunSidePanelWidth = 320.dp

/** 侧栏出现后会话列至少保留常见手机的宽度。 */
internal val RunSidePanelMinChatWidth = 360.dp

/**
 * 窗口宽度与高度都达到下限时运行信息移到侧栏，浮岛不再显示运行坞；横屏手机高度不足，仍用运行坞。
 * 宽度下限按侧栏、分隔线与会话列最小宽度相加，折叠屏内屏（约 690–810dp）也能放下侧栏。
 */
internal val RunSidePanelMinWindowWidth = RunSidePanelWidth + 1.dp + RunSidePanelMinChatWidth
internal val RunSidePanelMinWindowHeight = 480.dp

/** 侧栏计划区从当前步骤起列出的步数；其余在运行详情中查看。 */
internal const val RUN_SIDE_PLAN_ROWS = 4

/** 侧栏计划区要列出的叶子步骤：从第一个未完成的步骤起取 [limit] 步；全部完成时取最后几步。 */
internal fun runSidePlanSteps(steps: List<TaskPanelStep>, limit: Int = RUN_SIDE_PLAN_ROWS): List<TaskPanelStep> {
    val leaves = steps.filterNot { it.isGroup }
    val start = leaves.indexOfFirst { it.status == TaskPanelStepStatus.DOING || it.status == TaskPanelStepStatus.TODO }
    return if (start < 0) leaves.takeLast(limit) else leaves.drop(start).take(limit)
}

/**
 * 宽屏运行侧栏：顶部是运行状态与用时，下面依次是计划、后台任务、状态面板、Git 与本会话产物，
 * 底部是本次运行统计。各区点按进入对应的详情（运行详情、Git 面板、画布）。
 */
@Composable
internal fun RunSidePanel(
    dock: RunDockUiState?,
    plan: TaskPanelUiState?,
    elapsedMillis: Long?,
    progress: GenerationProgress?,
    activity: AgentActivitySummary?,
    stateBoard: StateBoardSnapshot?,
    git: GitEntrySummary.Repository?,
    canvases: List<CanvasMetadata>,
    onOpenRun: (RunDetailTab) -> Unit,
    onJumpToApproval: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenCanvas: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val paneLabel = stringResource(R.string.run_side_panel_label)
    Column(
        modifier = modifier
            .width(RunSidePanelWidth)
            .fillMaxHeight()
            .background(scheme.surfaceContainerLowest)
            .semantics { paneTitle = paneLabel }
            .testTag(UiTags.CHAT_RUN_SIDE_PANEL),
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SideHeader(dock, elapsedMillis, onJumpToApproval)
            if (plan != null && plan.totalCount > 0) {
                SideSection(
                    title = stringResource(R.string.run_detail_tab_plan),
                    meta = "${plan.doneCount}/${plan.totalCount}",
                    onClick = { onOpenRun(RunDetailTab.PLAN) },
                ) {
                    LinearProgressIndicator(
                        progress = { plan.doneCount.toFloat() / plan.totalCount },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        trackColor = scheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    runSidePlanSteps(plan.steps).forEach { step -> SidePlanRow(step, current = step.id == plan.currentStepId) }
                    if (plan.totalCount > RUN_SIDE_PLAN_ROWS) {
                        Text(
                            stringResource(R.string.run_side_view_all_steps, plan.totalCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                        )
                    }
                }
            }
            if (activity != null && activity.visible) {
                val meta = buildList {
                    if (activity.runningJobs > 0) add(pluralStringResource(R.plurals.run_side_jobs_running, activity.runningJobs, activity.runningJobs))
                    if (activity.enabledSchedules > 0) add(pluralStringResource(R.plurals.run_side_schedules, activity.enabledSchedules, activity.enabledSchedules))
                }.joinToString(" · ")
                SideSection(stringResource(R.string.run_dock_background), meta = null, onClick = { onOpenRun(RunDetailTab.BACKGROUND) }) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
            stateBoard?.board?.let { board ->
                SideSection(board.title, meta = null, onClick = { onOpenRun(RunDetailTab.STATE) }) {
                    board.fields.take(3).forEach { field ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(field.label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                stateValueText(field.value, field.unit),
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = scheme.onSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        }
                    }
                }
            }
            git?.let { summary ->
                val meta = listOfNotNull(
                    gitTrackingCount(summary.ahead, null)?.let { "↑$it" },
                    gitTrackingCount(summary.behind, null)?.let { "↓$it" },
                ).joinToString(" ")
                SideSection(stringResource(R.string.run_side_git), meta, onClick = onOpenGit) {
                    // 领先/落后已在标题行右侧，这里只写分支与改动数。
                    Text(gitSummaryLine(summary.copy(ahead = null, behind = null)), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (canvases.isNotEmpty()) {
                SideSection(stringResource(R.string.run_side_artifacts), canvases.size.toString(), onClick = null) {
                    canvases.take(3).forEach { canvas -> SideCanvasRow(canvas) { onOpenCanvas(canvas.id) } }
                }
            }
        }
        progress?.takeIf { it.rounds > 0 || it.calls > 0 || it.tokens > 0 }?.let {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Text(
                runStatsText(it),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SideHeader(dock: RunDockUiState?, elapsedMillis: Long?, onJumpToApproval: () -> Unit) {
    val waiting = dock?.pendingApprovals?.let { it > 0 } == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (waiting) Modifier.clickable(role = Role.Button, onClick = onJumpToApproval) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RunDockDot(dock?.tone ?: RunDockTone.IDLE)
        Column(Modifier.weight(1f)) {
            Text(
                dock?.let { runDockLabelText(it.label) } ?: stringResource(R.string.run_side_idle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            elapsedMillis?.let {
                Text(runElapsedText(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SideSection(
    title: String,
    meta: String?,
    onClick: (() -> Unit)?,
    content: (@Composable () -> Unit)? = null,
) {
    val label = stringResource(R.string.run_side_open_section, title)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!meta.isNullOrBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        content?.invoke()
    }
}

@Composable
private fun SidePlanRow(step: TaskPanelStep, current: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 28.dp)) {
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
            val dot = Modifier.size(9.dp)
            when {
                current -> Box(dot.background(scheme.primary, CircleShape))
                step.status == TaskPanelStepStatus.DONE -> Box(dot.background(scheme.outline, CircleShape))
                else -> Box(dot.border(1.6.dp, scheme.outline, CircleShape))
            }
        }
        Text(
            step.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (current) FontWeight.Medium else null,
            color = if (step.status == TaskPanelStepStatus.DONE) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SideCanvasRow(canvas: CanvasMetadata, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.canvas_open), onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(width = 56.dp, height = 38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Dashboard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(canvas.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.canvas_card_inline_version, canvas.currentVersion), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
