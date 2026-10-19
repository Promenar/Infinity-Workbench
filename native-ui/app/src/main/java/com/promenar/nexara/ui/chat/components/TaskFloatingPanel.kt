package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.chat.TaskPanelStep
import com.promenar.nexara.ui.chat.TaskPanelStepStatus
import com.promenar.nexara.ui.chat.TaskPanelUiState
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

/**
 * 输入框上方的计划条：收起时只占一行（状态、当前步骤、进度、展开与关闭），
 * 展开后显示目标、完整步骤与手动操作。关闭行为由调用方决定（隐藏或清除）。
 */
@Composable
fun TaskFloatingPanel(
    state: TaskPanelUiState,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(state.planId) { mutableStateOf(initiallyExpanded) }
    val toggleLabel = stringResource(
        if (expanded) R.string.chat_task_collapse_steps else R.string.chat_task_expand_steps,
    )
    val closeLabel = stringResource(
        if (state.isCompleted) R.string.chat_task_clear_completed else R.string.chat_task_hide,
    )
    val progress = if (state.totalCount == 0) 0f else state.doneCount.toFloat() / state.totalCount
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag(UiTags.CHAT_TASK_CARD),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                TaskPlanDetails(
                    state = state,
                    isGenerating = isGenerating,
                    onContinue = onContinue,
                    onComplete = onComplete,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .clickable(onClickLabel = toggleLabel, role = Role.Button) { expanded = !expanded }
                        .padding(start = NexaraSpacing.Medium)
                        .testTag(UiTags.CHAT_TASK_PANEL_HEADER),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                ) {
                    if (state.isCompleted) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        TaskStatusDot(
                            pulsing = state.shouldPulse,
                            color = if (state.shouldPulse) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                        )
                    }
                    Text(
                        text = if (state.isCompleted) {
                            stringResource(R.string.chat_task_all_completed)
                        } else {
                            state.currentStepTitle
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${state.doneCount}/${state.totalCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onClose,
                    enabled = !(state.isCompleted && isGenerating),
                    modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget).testTag(UiTags.CHAT_TASK_CLOSE),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = closeLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Medium, bottom = NexaraSpacing.Small)
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun TaskPlanDetails(
    state: TaskPanelUiState,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Medium, top = NexaraSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        if (state.title.isNotBlank()) {
            Text(
                text = stringResource(R.string.chat_task_title, state.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .testTag(UiTags.CHAT_TASK_STEP_LIST),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
        ) {
            state.steps.forEach { step -> TaskStepRow(step, pulsing = state.shouldPulse) }
        }
        if (!state.isCompleted) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall, Alignment.End),
            ) {
                TextButton(
                    onClick = onComplete,
                    enabled = !isGenerating,
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.CHAT_TASK_COMPLETE),
                ) {
                    Text(
                        text = stringResource(R.string.chat_task_mark_completed),
                        color = if (isGenerating) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(
                    onClick = onContinue,
                    enabled = !isGenerating,
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.CHAT_TASK_CONTINUE),
                ) {
                    Text(stringResource(R.string.chat_task_continue))
                }
            }
        }
    }
}

@Composable
private fun TaskStepRow(step: TaskPanelStep, pulsing: Boolean) {
    val statusLabel = stringResource(
        when (step.status) {
            TaskPanelStepStatus.TODO -> R.string.chat_task_step_todo
            TaskPanelStepStatus.DOING -> R.string.chat_task_step_doing
            TaskPanelStepStatus.DONE -> R.string.chat_task_step_done
            TaskPanelStepStatus.DROPPED -> R.string.chat_task_step_dropped
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NexaraSpacing.Medium * step.depth)
            .semantics(mergeDescendants = true) {
                if (!step.isGroup) stateDescription = statusLabel
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Box(modifier = Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when {
                step.isGroup -> Unit
                step.status == TaskPanelStepStatus.DONE -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                step.status == TaskPanelStepStatus.DROPPED -> Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                step.status == TaskPanelStepStatus.DOING -> TaskStatusDot(
                    pulsing = pulsing,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> TaskStatusDot(pulsing = false, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            text = step.title,
            modifier = Modifier.weight(1f),
            style = if (step.isGroup) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = when (step.status) {
                TaskPanelStepStatus.DONE, TaskPanelStepStatus.DROPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (step.status == TaskPanelStepStatus.DROPPED && !step.isGroup) {
                TextDecoration.LineThrough
            } else {
                null
            },
        )
    }
}

@Composable
fun TaskStatusDot(
    pulsing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scale = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "task_status_pulse")
        val animated by transition.animateFloat(
            initialValue = 0.82f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "task_status_scale",
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(12.dp)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(if (pulsing) 10.dp else 9.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = if (pulsing) color else Color.Transparent,
            border = if (pulsing) null else BorderStroke(1.5.dp, color),
            content = {},
        )
    }
}
