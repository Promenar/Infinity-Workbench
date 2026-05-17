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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
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
    RagPhase("query_intent", "分析查询意图", PhaseStatus.DONE, 100),
    RagPhase("vector_search", "向量库检索", PhaseStatus.DONE, 100),
    RagPhase("keyword_search", "关键词检索", PhaseStatus.DONE, 100),
    RagPhase("hybrid_merge", "混合检索融合", PhaseStatus.DONE, 100),
    RagPhase("kg_retrieval", "知识图谱关系检索", PhaseStatus.DONE, 100),
    RagPhase("rerank", "相关性重排过滤", PhaseStatus.DONE, 100),
    RagPhase("context_compress", "上下文提示词压缩", PhaseStatus.DONE, 100),
    RagPhase("prompt_build", "注入大模型上下文", PhaseStatus.DONE, 100)
)

@Composable
fun RagProgressCard(
    phases: List<RagPhase>,
    references: List<RagReference>?,
    kgPaths: List<KgPath>? = null,
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    var showDetailsSheet by remember { mutableStateOf(false) }

    val hasReferences = references?.isNullOrEmpty() == false
    val hasKgPaths = kgPaths?.isNullOrEmpty() == false

    // 如果是历史消息（phases 为空且已完成），自动回退使用默认的 8 步已完成状态填充
    val displayPhases = if (phases.isEmpty() && isComplete) {
        RAG_DEFAULT_PHASES
    } else {
        phases
    }

    val activePhase = displayPhases.find { it.status == PhaseStatus.ACTIVE }

    // 极端保护：无数据时不展示
    if (activePhase == null && isComplete == false && hasReferences == false && displayPhases.isEmpty()) return

    // 自动匹配当前精细状态描述
    val currentText = when {
        isComplete -> "✓ 知识检索就绪"
        activePhase != null -> {
            val name = activePhase.name
            val detail = activePhase.detail
            if (!detail.isNullOrBlank()) "$name • $detail" else name
        }
        displayPhases.isNotEmpty() -> "正在准备检索上下文..."
        else -> "✓ 检索就绪"
    }

    NexaraGlassCard(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .padding(vertical = 4.dp)
            .clickable(enabled = hasReferences || hasKgPaths) { showDetailsSheet = true },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 第一行：极简单行状态与百分比
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 雷达旋转加载动画
                    val infiniteTransition = rememberInfiniteTransition(label = "rag_radar")
                    val rotationAngle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "angle"
                    )

                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isComplete) 0f else rotationAngle)
                    ) {
                        if (isComplete) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                null,
                                tint = NexaraColors.StatusSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            NexaraColors.Primary.copy(alpha = 0.2f),
                                            NexaraColors.Primary,
                                            NexaraColors.Tertiary,
                                            NexaraColors.Primary.copy(alpha = 0.2f)
                                        )
                                    ),
                                    startAngle = 0f,
                                    sweepAngle = 300f,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 1.5.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                )
                            }
                        }
                    }

                    // 智能垂直翻页文本
                    AnimatedContent(
                        targetState = currentText,
                        transitionSpec = {
                            (slideInVertically { height -> height } + fadeIn())
                                .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                                .using(SizeTransform(clip = false))
                        },
                        label = "rag_text",
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        Text(
                            text = text,
                            style = NexaraTypography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = if (isComplete) NexaraColors.OnSurfaceVariant.copy(alpha = 0.8f) else NexaraColors.OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 右侧微标
                if (isComplete) {
                    Text(
                        text = "Done",
                        style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.StatusSuccess
                    )
                } else if (activePhase != null) {
                    Text(
                        text = "${activePhase.progress}%",
                        style = NexaraTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = NexaraColors.Primary
                    )
                } else {
                    Text(
                        text = "Active",
                        style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.Primary
                    )
                }
            }

            if (displayPhases.isNotEmpty()) {
                NeonMicroRail(phases = displayPhases, isComplete = isComplete)
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

@Composable
private fun NeonMicroRail(
    phases: List<RagPhase>,
    isComplete: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer_flow")
        
        // 活跃段的光晕呼吸系数
        val activeGlowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.38f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glow_alpha"
        )

        phases.forEach { phase ->
            val status = if (isComplete) PhaseStatus.DONE else phase.status
            val weightModifier = Modifier.weight(1f)

            when (status) {
                PhaseStatus.DONE -> {
                    val neonGreen = Color(0xFF00FF66)
                    Canvas(modifier = weightModifier.height(8.dp)) {
                        // 1. 底层半透明发光层（高度 6.dp）
                        drawRoundRect(
                            color = neonGreen.copy(alpha = 0.25f),
                            size = androidx.compose.ui.geometry.Size(size.width, 6.dp.toPx()),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 6.dp.toPx()) / 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                        // 2. 顶层高亮实体核心段（高度 3.dp）
                        drawRoundRect(
                            color = neonGreen,
                            size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 3.dp.toPx()) / 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                        )
                    }
                }
                PhaseStatus.ACTIVE -> {
                    val progressFloat = phase.progress / 100f
                    val animatedProgress by animateFloatAsState(
                        targetValue = progressFloat,
                        animationSpec = spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        label = "active_progress"
                    )

                    val neonPurple = Color(0xFFB026FF)
                    val innerHighlightColor = Color(0xFFFDF4FF)

                    Canvas(modifier = weightModifier.height(8.dp)) {
                        // 1. 绘制准备底轨
                        drawRoundRect(
                            color = Color(0xFF3F3F46).copy(alpha = 0.35f),
                            size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 3.dp.toPx()) / 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                        )

                        val activeWidth = size.width * animatedProgress
                        if (activeWidth > 0f) {
                            // 2. 绘制填充段发光层
                            drawRoundRect(
                                color = neonPurple.copy(alpha = activeGlowAlpha),
                                size = androidx.compose.ui.geometry.Size(activeWidth, 6.dp.toPx()),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 6.dp.toPx()) / 2f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                            )
                            // 3. 绘制填充段实体层
                            drawRoundRect(
                                color = neonPurple,
                                size = androidx.compose.ui.geometry.Size(activeWidth, 3.dp.toPx()),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 3.dp.toPx()) / 2f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                            )
                            // 4. 极细高亮灯丝核心线
                            drawRoundRect(
                                color = innerHighlightColor.copy(alpha = 0.85f),
                                size = androidx.compose.ui.geometry.Size(activeWidth, 1.dp.toPx()),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 1.dp.toPx()) / 2f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.5.dp.toPx())
                            )
                        }
                    }
                }
                PhaseStatus.PENDING -> {
                    val darkGrey = Color(0xFF3F3F46)
                    Canvas(modifier = weightModifier.height(8.dp)) {
                        drawRoundRect(
                            color = darkGrey.copy(alpha = 0.5f),
                            size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - 2.dp.toPx()) / 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                        )
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
private fun PostProcessChip(
    task: PostProcessTask,
    onRemove: () -> Unit
) {
    val label = when (task.type) {
        PostProcessType.ARCHIVE_TO_RAG -> "Memory"
        PostProcessType.AUTO_SUMMARY -> "Summary"
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

    NexaraGlassCard(
        shape = RoundedCornerShape(50),
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

    NexaraGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp)
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

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
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
