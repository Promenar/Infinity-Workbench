package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.chat.TaskPanelUiState
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskFloatingPanel(
    state: TaskPanelUiState,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.CHAT_TASK_CARD),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NexaraSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CHAT_TASK_PANEL_HEADER),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Text(
                    text = stringResource(R.string.chat_task_title, state.title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.doneCount}/${state.totalCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                TaskStatusDot(
                    pulsing = state.shouldPulse,
                    color = if (state.shouldPulse) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Text(
                    text = state.currentStepTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
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
