// UNIT TEST EXEMPTION STATEMENT: 本文件仅涉及 Jetpack Compose 各种聊天辅助组件（进度条、思考框、审批卡等）的 UI 渲染与排版布局逻辑，不包含任何数据模型算法判定，故依全局开发规范 §3.4 予以单元测试豁免。
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.PostProcessType
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagProgress
import com.promenar.nexara.data.model.RagReference
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.nexara.ui.chat.components.RagDetailsSheet
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraElevation
import com.promenar.nexara.ui.theme.NexaraSpacing
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

    // Auto-expand during generation, auto-collapse when generation finishes
    LaunchedEffect(reasoning.isNotBlank(), isGenerating) {
        if (reasoning.isNotBlank() && isGenerating) {
            isExpanded = true
        } else if (!isGenerating) {
            isExpanded = false
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
                            fontSize = (fontSize - 2).coerceAtLeast(10).sp,
                            lineHeight = (fontSize + 3).sp,
                            fontStyle = FontStyle.Italic
                        )
                    ) {
                        MarkdownText(
                            markdown = reasoning,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = isGenerating,
                            fontSize = (fontSize - 2).coerceAtLeast(10),
                            showCursor = false // Hide cursor in thinking block to avoid double cursors
                        )
                    }
                }
            }
        }
    }
}

@Deprecated("Use RagProgressCard instead", ReplaceWith("RagProgressCard"))
@Composable
fun RagOmniIndicator(
    progress: RagProgress?,
    metadata: RagMetadata?,
    references: List<RagReference>?,
    kgPaths: List<KgPath>? = null,
    isLoading: Boolean
) {
    var showDetailsSheet by remember { mutableStateOf(false) }

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
            .padding(vertical = 4.dp)
            .clickable(enabled = (!references.isNullOrEmpty() || !kgPaths.isNullOrEmpty())) { showDetailsSheet = true },
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

    if (showDetailsSheet) {
        RagDetailsSheet(
            references = references,
            kgPaths = kgPaths,
            onDismissRequest = { showDetailsSheet = false }
        )
    }
}

private val RAG_DEFAULT_PHASES = listOf(
    RagPhase("query_intent", "query_intent", PhaseStatus.DONE, 100),
    RagPhase("vector_search", "vector_search", PhaseStatus.DONE, 100),
    RagPhase("keyword_search", "keyword_search", PhaseStatus.DONE, 100),
    RagPhase("hybrid_merge", "hybrid_merge", PhaseStatus.DONE, 100),
    RagPhase("kg_retrieval", "kg_retrieval", PhaseStatus.DONE, 100),
    RagPhase("rerank", "rerank", PhaseStatus.DONE, 100),
    RagPhase("context_compress", "context_compress", PhaseStatus.DONE, 100),
    RagPhase("prompt_build", "prompt_build", PhaseStatus.DONE, 100)
)

@Composable
private fun localizedRagPhaseName(phase: RagPhase): String {
    val resourceId = when (phase.id) {
        "query_intent" -> R.string.chat_rag_phase_query_intent
        "embed" -> R.string.chat_rag_phase_embedding
        "memory" -> R.string.chat_rag_phase_memory
        "vector_search", "docs" -> R.string.chat_rag_phase_documents
        "keyword_search" -> R.string.chat_rag_phase_keyword
        "hybrid_merge", "hybrid" -> R.string.chat_rag_phase_hybrid
        "rank" -> R.string.chat_rag_phase_ranking
        "rerank" -> R.string.chat_rag_phase_rerank
        "kg_retrieval", "kg" -> R.string.chat_rag_phase_knowledge_graph
        "context_compress" -> R.string.chat_rag_phase_context_compress
        "prompt_build" -> R.string.chat_rag_phase_prompt_build
        "ready" -> R.string.chat_rag_phase_context_ready
        "retrieved" -> R.string.chat_rag_phase_retrieved
        else -> null
    }
    return resourceId?.let { stringResource(it) } ?: phase.name
}

@Composable
fun RagProgressCard(
    phases: List<RagPhase>,
    references: List<RagReference>?,
    kgPaths: List<KgPath>? = null,
    citations: List<Citation>? = null,
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    var showDetailsSheet by remember { mutableStateOf(false) }

    val hasReferences = references?.isNullOrEmpty() == false
    val hasKgPaths = kgPaths?.isNullOrEmpty() == false
    val hasCitations = citations?.isNullOrEmpty() == false

    // 如果是历史消息（phases 为空且已完成），自动回退使用默认的 8 步已完成状态填充
    val displayPhases = if (phases.isEmpty() && isComplete) {
        RAG_DEFAULT_PHASES
    } else {
        phases
    }

    val activePhase = displayPhases.find { it.status == PhaseStatus.ACTIVE }
    val visiblePhase = activePhase
        ?: displayPhases.lastOrNull { it.id == "ready" && it.status == PhaseStatus.DONE }
    val retrievalReady = isComplete || visiblePhase?.id == "ready"

    // 自动匹配当前精细状态描述
    val currentText = when {
        isComplete -> {
            if (hasCitations && (hasReferences || hasKgPaths)) stringResource(R.string.chat_rag_ready_references)
            else if (hasCitations) stringResource(R.string.chat_rag_ready_web)
            else stringResource(R.string.chat_rag_ready_knowledge)
        }
        visiblePhase != null -> localizedRagPhaseName(visiblePhase)
        displayPhases.isNotEmpty() -> stringResource(R.string.chat_rag_preparing)
        else -> {
            if (hasCitations && (hasReferences || hasKgPaths)) stringResource(R.string.chat_rag_ready_references)
            else if (hasCitations) stringResource(R.string.chat_rag_ready_web)
            else stringResource(R.string.chat_rag_ready_generic)
        }
    }

    val progress = when {
        retrievalReady -> 1f
        activePhase != null -> (activePhase.progress.coerceIn(0, 100) / 100f)
        else -> 0f
    }
    val openDetailsLabel = stringResource(R.string.chat_rag_open_details)
    val doneLabel = stringResource(R.string.chat_rag_done)
    val openDetails = { showDetailsSheet = true }
    val accessibilityState = if (retrievalReady) "$currentText · $doneLabel" else currentText

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clickable(
                onClickLabel = openDetailsLabel,
                role = Role.Button,
                onClick = openDetails,
            )
            .clearAndSetSemantics {
                semanticsTestTag = UiTags.RAG_PROGRESS_CARD
                role = Role.Button
                stateDescription = accessibilityState
                onClick(label = openDetailsLabel) {
                    openDetails()
                    true
                }
                if (!retrievalReady) {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = NexaraElevation.Level0
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaraSpacing.Large,
                vertical = NexaraSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        ) {
            if (retrievalReady) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .size(18.dp)
                        .clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = currentText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (retrievalReady) {
                    stringResource(R.string.chat_rag_done)
                } else {
                    "${(progress * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }

    if (showDetailsSheet) {
        RagDetailsSheet(
            references = references,
            kgPaths = kgPaths,
            citations = citations,
            onDismissRequest = { showDetailsSheet = false }
        )
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
                
            }
        }
    }
}

@Composable
fun PostProcessBar(
    tasks: List<PostProcessTask>,
    onRemoveTask: (String) -> Unit
) {
    if (tasks.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tasks.forEach { task ->
            PostProcessChip(
                task = task,
                onRemove = { onRemoveTask(task.id) }
            )
        }
    }
}

@Composable
fun PostProcessChip(
    task: PostProcessTask,
    onRemove: () -> Unit
) {
    val label = when (task.type) {
        PostProcessType.ARCHIVE_TO_RAG -> stringResource(R.string.chat_postprocess_memory)
        PostProcessType.AUTO_SUMMARY -> stringResource(R.string.chat_postprocess_summary)
    }

    val iconColor = when (task.status) {
        PostProcessStatus.RUNNING -> NexaraColors.Primary
        PostProcessStatus.DONE -> NexaraColors.StatusSuccess
        PostProcessStatus.ERROR -> NexaraColors.StatusError
    }

    val icon = when (task.status) {
        PostProcessStatus.RUNNING -> Icons.Rounded.Sync
        PostProcessStatus.DONE -> Icons.Rounded.CheckCircle
        PostProcessStatus.ERROR -> Icons.Rounded.Error
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (task.status == PostProcessStatus.RUNNING) {
                val infiniteTransition = rememberInfiniteTransition(label = "pp_pulse_${task.id}")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pp_alpha_${task.id}"
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier
                        .size(12.dp)
                        .alpha(alpha)
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(12.dp)
                )
            }

            Text(
                text = label,
                style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                color = NexaraColors.OnSurfaceVariant
            )
        }
    }

    if (task.status == PostProcessStatus.DONE) {
        LaunchedEffect(task.id) {
            kotlinx.coroutines.delay(3000)
            onRemove()
        }
    }
}

@Composable
fun SummaryCard(
    isCompressing: Boolean,
    progress: Float,
    detail: String,
    result: String?,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NexaraSpacing.XSmall),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = NexaraElevation.Level0
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
                    if (isCompressing) {
                        val infiniteTransition = rememberInfiniteTransition(label = "summary_pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "summary_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .alpha(alpha)
                                .background(NexaraColors.Primary)
                        )
                    } else {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = NexaraColors.StatusSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_summary_card_title),
                        style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface
                    )
                }

                Surface(
                    color = if (isCompressing) NexaraColors.Primary.copy(alpha = 0.1f) else NexaraColors.StatusSuccess.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isCompressing) stringResource(R.string.chat_summary_card_compressing) else stringResource(R.string.chat_summary_card_done),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = NexaraTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (isCompressing) NexaraColors.Primary else NexaraColors.StatusSuccess
                    )
                }
            }

            if (isCompressing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = detail,
                        style = NexaraTypography.labelSmall.copy(fontSize = 11.sp),
                        color = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = NexaraTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = NexaraColors.Primary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(NexaraColors.SurfaceHigh)
                ) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(500),
                        label = "summary_progress"
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

            if (result != null) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = NexaraColors.OutlineVariant.copy(alpha = 0.2f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isExpanded) stringResource(R.string.chat_summary_card_collapse) else stringResource(R.string.chat_summary_card_expand),
                        style = NexaraTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = NexaraColors.Primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        color = NexaraColors.SurfaceLow.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = result,
                            style = NexaraTypography.bodyMedium.copy(fontSize = 15.sp),
                            color = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NexaraSpacing.Small)
            .testTag(UiTags.CHAT_APPROVAL_CARD),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = NexaraElevation.Level0,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
                    modifier = Modifier.testTag(
                        if (isExecuted) UiTags.CHAT_APPROVAL_EXECUTED else UiTags.CHAT_APPROVAL_REQUIRED
                    ),
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
                            text = stringResource(R.string.chat_approval_executed_at, executionTime),
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
                            modifier = Modifier
                                .weight(1f)
                                .testTag(UiTags.CHAT_APPROVAL_DECLINE),
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
                            modifier = Modifier
                                .weight(1f)
                                .testTag(UiTags.CHAT_APPROVAL_APPROVE),
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
