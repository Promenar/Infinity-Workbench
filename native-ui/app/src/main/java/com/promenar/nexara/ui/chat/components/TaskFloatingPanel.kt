package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class TaskCapsuleMode {
    GENERATING,
    PENDING
}

data class TaskPanelState(
    val completedLeafCount: Int,
    val totalLeafCount: Int,
    val unfinishedLeafCount: Int,
    val firstUnfinishedLeaf: TaskStep,
    val capsuleMode: TaskCapsuleMode,
    val shouldPulse: Boolean
)

fun taskPanelState(steps: List<TaskStep>, isGenerating: Boolean): TaskPanelState? {
    val leaves = buildList {
        fun collect(nodes: List<TaskStep>) {
            nodes.sortedBy { it.sortOrder }.forEach { step ->
                if (step.children.isEmpty()) add(step) else collect(step.children)
            }
        }
        collect(steps)
    }
    val unfinished = leaves.filter { it.status != "done" }
    val firstUnfinished = unfinished.firstOrNull { it.status == "doing" }
        ?: unfinished.firstOrNull()
        ?: return null
    val completed = leaves.count { it.status == "done" }
    return TaskPanelState(
        completedLeafCount = completed,
        totalLeafCount = leaves.size,
        unfinishedLeafCount = unfinished.size,
        firstUnfinishedLeaf = firstUnfinished,
        capsuleMode = if (isGenerating) TaskCapsuleMode.GENERATING else TaskCapsuleMode.PENDING,
        shouldPulse = isGenerating
    )
}

@Composable
fun TaskFloatingPanel(
    activeTree: List<TaskStep>,
    goalTitle: String,
    isGenerating: Boolean,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = taskPanelState(activeTree, isGenerating) ?: return
    val displayGoal = goalTitle.ifBlank { activeTree.firstOrNull()?.title.orEmpty() }

    NexaraGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_task_card_title, displayGoal),
                    style = NexaraTypography.labelLarge,
                    color = NexaraColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${state.completedLeafCount}/${state.totalLeafCount}",
                    style = NexaraTypography.labelLarge,
                    color = NexaraColors.Primary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskStatusMarker(shouldPulse = state.shouldPulse)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.firstUnfinishedLeaf.title,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onContinue,
                    enabled = !isGenerating,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = NexaraColors.Primary)
                ) {
                    Text(stringResource(R.string.chat_task_continue))
                }
                TextButton(
                    onClick = onComplete,
                    enabled = !isGenerating,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = NexaraColors.OnSurfaceVariant)
                ) {
                    Text(stringResource(R.string.chat_task_mark_complete))
                }
            }
        }
    }
}

@Composable
private fun TaskStatusMarker(shouldPulse: Boolean) {
    if (shouldPulse) {
        val transition = rememberInfiniteTransition(label = "task_generating_pulse")
        val scale by transition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "task_generating_scale"
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(NexaraColors.Primary)
        )
    } else {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .border(1.dp, NexaraColors.StatusWarning, CircleShape)
        )
    }
}
