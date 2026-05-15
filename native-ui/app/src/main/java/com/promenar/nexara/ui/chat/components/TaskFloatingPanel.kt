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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun TaskFloatingPanel(
    sessionId: String,
    taskRepo: ITaskRepository?,
    goalTitle: String = "",
    modifier: Modifier = Modifier
) {
    if (taskRepo == null) return

    val activeTree by taskRepo.observeActiveTree(sessionId).collectAsState(emptyList())
    if (activeTree.isEmpty()) return

    val (doneCount, totalCount) = taskRepo.countLeafProgress(activeTree)
    val progress = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f
    val percent = if (totalCount > 0) (doneCount * 100 / totalCount) else 0

    val displayGoal = goalTitle.ifBlank {
        activeTree.firstOrNull()?.title ?: ""
    }

    var isCollapsed by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        NexaraGlassCard(
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isCollapsed = !isCollapsed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\uD83C\uDFAF $displayGoal",
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$doneCount/$totalCount \u6B65\u9AA4 \u00B7 $percent%",
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.Primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                        contentDescription = if (isCollapsed) "\u5C55\u5F00" else "\u6536\u8D77",
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(
                    visible = !isCollapsed,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = NexaraColors.Primary,
                            trackColor = NexaraColors.SurfaceHighest,
                            strokeCap = StrokeCap.Round
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = NexaraColors.GlassBorder
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(activeTree, key = { it.id }) { rootNode ->
                                TaskNodeRow(
                                    node = rootNode,
                                    depth = 0,
                                    taskRepo = taskRepo
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskNodeRow(
    node: TaskStep,
    depth: Int,
    taskRepo: ITaskRepository
) {
    val indent = (depth * 16).dp

    val isLeaf = node.children.isEmpty()
    val status: String = if (isLeaf) {
        node.status
    } else {
        taskRepo.deriveParentStatus(node.children)
    }

    val statusIcon: @Composable () -> Unit = {
        when {
            status == "done" -> Text(
                text = "\u2705",
                style = NexaraTypography.bodyMedium
            )
            status == "doing" -> PulseDotInline(color = NexaraColors.Primary)
            status == "dropped" -> Text(
                text = "\u2715",
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.Error
            )
            status == "partial-dropped" -> Text(
                text = "\u2298",
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.StatusWarning
            )
            else -> Text(
                text = "\u25CB",
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )
        }
    }

    val textColor = if (status == "doing") NexaraColors.Primary else NexaraColors.OnSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            statusIcon()
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = node.title,
            style = NexaraTypography.bodyMedium,
            color = textColor
        )
    }

    if (node.children.isNotEmpty()) {
        node.children.sortedBy { it.sortOrder }.forEach { child ->
            TaskNodeRow(
                node = child,
                depth = depth + 1,
                taskRepo = taskRepo
            )
        }
    }
}

@Composable
private fun PulseDotInline(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "task_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "taskPulseScale"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
    )
}
