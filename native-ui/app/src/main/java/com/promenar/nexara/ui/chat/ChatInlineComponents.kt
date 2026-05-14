package com.promenar.nexara.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

/**
 * Summary Indicator: A pulsing pill for high-level status (e.g., "Analyzing document structure...")
 */
@Composable
fun SummaryIndicator(
    text: String,
    modifier: Modifier = Modifier
) {
    NexaraGlassCard(
        modifier = modifier
            .wrapContentWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .alpha(alpha)
                    .background(NexaraColors.Primary)
            )
            Text(
                text = text,
                style = NexaraTypography.labelMedium,
                color = NexaraColors.Primary
            )
        }
    }
}

@Composable
fun ThinkingBlock(
    reasoning: String,
    isGenerating: Boolean,
    fontSize: Int = 13
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Auto-expand when reasoning starts appearing during generation
    LaunchedEffect(reasoning.isNotBlank(), isGenerating) {
        if (reasoning.isNotBlank() && isGenerating) {
            isExpanded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        NexaraGlassCard(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clickable { isExpanded = !isExpanded },
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isGenerating) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .alpha(alpha)
                            .background(NexaraColors.Primary)
                    )
                } else {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                
                Text(
                    text = if (isGenerating) stringResource(R.string.chat_status_thinking) else stringResource(R.string.chat_status_thought),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary
                )
                
                if (reasoning.isNotBlank()) {
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded && reasoning.isNotBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                color = NexaraColors.SurfaceLow.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.95f),
                border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides NexaraTypography.bodySmall.copy(
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = (fontSize - 1).coerceAtLeast(11).sp,
                            lineHeight = (fontSize + 4).sp
                        )
                    ) {
                        MarkdownText(
                            markdown = reasoning,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = isGenerating,
                            fontSize = (fontSize - 1).coerceAtLeast(11),
                            showCursor = false // Hide cursor in thinking block to avoid double cursors
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RagOmniIndicator(
    progress: RagProgress?,
    metadata: RagMetadata?,
    references: List<RagReference>?,
    isLoading: Boolean
) {
    val showProgress = when {
        isLoading -> true
        progress == null -> false
        (progress.percentage ?: 0) < 100 -> true
        else -> false
    }

    if (!showProgress && (references == null || references.isEmpty())) return

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudSync,
                        null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_rag_knowledge_retrieval),
                        style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface
                    )
                }

                if (showProgress) {
                    Surface(
                        color = NexaraColors.Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = stringResource(R.string.chat_rag_active),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = NexaraColors.Primary
                        )
                    }
                }
            }

            if (showProgress) {
                val percentage = progress?.percentage ?: if (isLoading) 30 else 0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = progress?.stage ?: stringResource(R.string.chat_rag_scanning),
                            style = NexaraTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = NexaraColors.OnSurfaceVariant
                        )
                        Text(
                            text = "$percentage%",
                            style = NexaraTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = NexaraColors.Primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(NexaraColors.SurfaceHigh)
                    ) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = percentage / 100f,
                            animationSpec = tween(500),
                            label = "rag_progress"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(NexaraColors.Primary, NexaraColors.Tertiary)
                                    )
                                )
                        )
                    }
                }
            }

            // References
            if (references != null && references.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp),
                    thickness = 0.5.dp,
                    color = NexaraColors.OutlineVariant.copy(alpha = 0.2f)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(references) { ref ->
                        Surface(
                            color = NexaraColors.SurfaceContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.Description, null, tint = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(10.dp))
                                Text(
                                    text = ref.source.substringAfterLast("/"),
                                    style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                                    color = NexaraColors.OnSurfaceVariant
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
fun ToolExecutionTimeline(
    steps: List<ExecutionStep>,
    isExecuting: Boolean = false
) {
    if (steps.isEmpty()) return

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.SurfaceHigh)
                        .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        null,
                        tint = NexaraColors.Tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.chat_tool_execution_pipeline),
                        style = NexaraTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface
                    )
                    Text(
                        text = if (isExecuting) stringResource(R.string.chat_tool_pipeline_running) else stringResource(R.string.chat_tool_pipeline_completed),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 12.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    TimelineStep(
                        step = step,
                        isLast = index == steps.size - 1,
                        isActive = isExecuting && (index == steps.size - 1)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineStep(
    step: ExecutionStep,
    isLast: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLast) 0.dp else 20.dp)
    ) {
        // Vertical Line & Dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp)
        ) {
            val dotColor = if (isActive) NexaraColors.Tertiary else NexaraColors.Primary
            val dotAlpha by if (isActive) {
                rememberInfiniteTransition(label = "dot_pulse").animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "alpha"
                )
            } else {
                remember { mutableStateOf(1f) }
            }

            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .alpha(dotAlpha)
                    .background(dotColor)
                    .border(2.dp, NexaraColors.SurfaceLow, CircleShape)
            )
            
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .background(NexaraColors.OutlineVariant.copy(alpha = 0.6f))
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.toolName ?: step.type,
                style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isActive) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
            )
            
            if (!step.toolArgs.isNullOrBlank()) {
                Surface(
                    color = NexaraColors.SurfaceLow.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                    border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = step.toolArgs,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = NexaraTypography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isActive) stringResource(R.string.chat_tool_running) else stringResource(R.string.chat_tool_completed),
                    style = NexaraTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    color = if (isActive) NexaraColors.Tertiary else NexaraColors.Primary.copy(alpha = 0.7f)
                )
                
                // Add execution time if available (Mock for now to match design)
                if (!isActive) {
                    Text(
                        text = "1.2s", // In real app, would come from step.duration
                        style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                        color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun ApprovalCard(
    toolName: String,
    description: String,
    isExecuted: Boolean = false,
    executionTime: String? = null,
    onApprove: () -> Unit = {},
    onDecline: () -> Unit = {}
) {
    val accentColor = if (isExecuted) NexaraColors.Primary else NexaraColors.Tertiary

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent border
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )
            
            Column(
                modifier = Modifier.padding(16.dp).alpha(if (isExecuted) 0.7f else 1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isExecuted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = (if (isExecuted) stringResource(R.string.chat_approval_executed) else stringResource(R.string.chat_approval_required)).uppercase(),
                        style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = accentColor
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = toolName,
                        style = NexaraTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface
                    )
                    if (!isExecuted) {
                        Text(
                            text = description,
                            style = NexaraTypography.bodySmall,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    } else if (executionTime != null) {
                        Text(
                            text = "Approved by You at $executionTime",
                            style = NexaraTypography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                if (!isExecuted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onDecline,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexaraColors.SurfaceHigh,
                                contentColor = NexaraColors.OnSurface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.common_decline), style = NexaraTypography.labelMedium)
                        }
                        Button(
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexaraColors.Tertiary,
                                contentColor = NexaraColors.OnTertiary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Text(stringResource(R.string.common_approve), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}
