package com.promenar.nexara.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.delay
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────
//  Pipeline 数据结构
// ─────────────────────────────────────────────────────────────────

/**
 * 将连续 ASSISTANT+TOOL 消息合并为一组。
 * USER 消息始终独立成组。
 */
data class PipelineGroup(
    val messages: List<Message>,
    val isUser: Boolean
) {
    /** 组内所有 ASSISTANT 消息 */
    val assistantMessages: List<Message>
        get() = messages.filter { it.role == MessageRole.ASSISTANT }
}

fun buildPipelineGroups(messages: List<Message>): List<PipelineGroup> {
    val groups = mutableListOf<PipelineGroup>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role == MessageRole.USER) {
            // USER 消息：每个独立成组（与后续 AI 回复分离以保持锚定语义）
            groups.add(PipelineGroup(listOf(msg), isUser = true))
            i++
        } else {
            // ASSISTANT / TOOL：视作同一轮 AI 响应，全部合并到一组
            val groupMsgs = mutableListOf<Message>()
            while (i < messages.size && messages[i].role != MessageRole.USER) {
                groupMsgs.add(messages[i])
                i++
            }
            groups.add(PipelineGroup(groupMsgs, isUser = false))
        }
    }
    return groups
}

// ─────────────────────────────────────────────────────────────────
//  PipelineBubble — 单轮 AI 对话的线性格局
// ─────────────────────────────────────────────────────────────────

/**
 * 将一组 ASSISTANT+TOOL 消息渲染为单一气泡，
 * 内部以思考→工具→正文 的线性管道排列，步骤间以竖线连接。
 */
@Composable
fun PipelineBubble(
    group: PipelineGroup,
    isGenerating: Boolean,
    status: GenerationStatus = GenerationStatus.IDLE,
    streamingContent: String,
    fontSize: Int,
    onContentChange: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (group.isUser) {
        // USER 消息保持原样
        UserMessageBubble(
            message = group.messages.first(),
            fontSize = fontSize
        )
        return
    }

    val allSteps = remember(group.messages) { buildPipelineSteps(group.messages) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            allSteps.forEachIndexed { index, step ->
                val isLastInGroup = index == allSteps.lastIndex
                
                // ── 步骤渲染 ──
                when (step) {
                    is PipelineStep.Thinking -> {
                        // 仅在当前确实处于思考状态且是最后一步思考时，才开启流式平滑渲染
                        val isLastThinking = allSteps.filterIsInstance<PipelineStep.Thinking>().lastOrNull() == step
                        // 推理活跃判定：整体生成中 + 最后一步思考 + 正文尚未开始（streamingContent 只累积 content，不含 reasoning）
                        val isThinkingStreaming = isGenerating && isLastThinking && streamingContent.isEmpty()
                        
                        InlineThinkingRow(
                            reasoning = step.reasoning,
                            isGenerating = isThinkingStreaming,
                            fontSize = fontSize
                        )
                    }
                    is PipelineStep.ToolExec -> {
                        InlineToolRow(
                            steps = step.steps,
                            isExecuting = step.isExecuting,
                            fontSize = fontSize
                        )
                    }
                    is PipelineStep.Content -> {
                        val displayContent = if (isLastInGroup && isGenerating) {
                            streamingContent.ifEmpty { step.content }
                        } else {
                            step.content
                        }
                        ContentSegment(
                            content = displayContent,
                            isStreaming = isLastInGroup && isGenerating,
                            fontSize = fontSize,
                            onContentChange = onContentChange
                        )
                    }
                }

                // ── 步骤间的垂直连接线 (仅在有后续步骤时显示) ──
                if (index < allSteps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 20.dp, top = 2.dp, bottom = 2.dp)
                            .width(1.dp)
                            .height(12.dp)
                            .background(NexaraColors.OutlineVariant.copy(alpha = 0.8f))
                    )
                }
            }

            // ── 生成中闪烁光标：仅在 pipeline 无 Content 步骤时（TTFT 期）渲染 ──
            //    有 Content 步骤时由 MarkdownText 内部的 StreamingCursor 接管
            val hasContentStep = allSteps.any { it is PipelineStep.Content }
            if (isGenerating && !hasContentStep) {
                StreamingCursor()
            }
        }
    }

    // ── 元信息行（模型名 + 时间戳）──
    group.assistantMessages.lastOrNull()?.let { lastMsg ->
        val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
        val timestamp = remember(lastMsg.createdAt) { timeFormat.format(java.util.Date(lastMsg.createdAt)) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            val metaStyle = NexaraTypography.labelSmall.copy(
                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            if (!lastMsg.modelId.isNullOrBlank()) {
                Text(
                    text = lastMsg.modelId!!,
                    style = metaStyle,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(text = timestamp, style = metaStyle)
        }
    }

    // ── 错误信息 ──
    group.messages.lastOrNull()?.let { lastMsg ->
        if (lastMsg.isError && lastMsg.errorMessage != null) {
            Text(
                text = lastMsg.errorMessage!!,
                style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                color = NexaraColors.Error,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Pipeline 步骤构建
// ─────────────────────────────────────────────────────────────────

private sealed class PipelineStep {
    data class Thinking(
        val reasoning: String
    ) : PipelineStep()

    data class ToolExec(
        val steps: List<ExecutionStep>,
        val isExecuting: Boolean
    ) : PipelineStep()

    data class Content(
        val content: String,
    ) : PipelineStep()
}

// ── 工具内容嗅探：覆盖 JSON 代码块、裸 JSON、分隔符文本、XML 四种格式 ──
private val TOOL_CONTENT_SNIFFER = Regex(
    """(?:
        ```(?:json)?\s*\n?\{[\s\S]*?"(?:query|name|tool|function)"[\s\S]*?\}\s*\n?```
        |\{[^}]*"(?:tool_name|tool_call_id|query|search|top_n|top_k)"[^}]*\}
        |---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]
        |<(?:tool_call|function_call|function_name)[\s\S]*?/>
    )""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
)

private fun hasInlineToolData(content: String): Boolean =
    TOOL_CONTENT_SNIFFER.containsMatchIn(content)

private fun stripToolArtifacts(content: String): String {
    var clean = content
    clean = TOOL_CONTENT_SNIFFER.replace(clean, "")
    return clean.trim()
}

private fun buildSyntheticExecutionSteps(
    toolCalls: List<String>,
    toolResults: List<String>
): List<ExecutionStep> {
    if (toolCalls.isEmpty() && toolResults.isEmpty()) return emptyList()

    val steps = mutableListOf<ExecutionStep>()
    val timestamp = System.currentTimeMillis()

    toolCalls.forEachIndexed { index, args ->
        val toolName = runCatching {
            JSONObject(args).optString("name")
                ?: JSONObject(args).optString("tool")
                ?: JSONObject(args).optString("function")
                ?: "工具调用"
        }.getOrDefault("工具调用")

        steps.add(
            ExecutionStep(
                id = "synthetic-tool-$timestamp-$index",
                type = "tool_call",
                toolName = toolName,
                toolArgs = args,
                timestamp = timestamp + index
            )
        )
    }

    toolResults.forEachIndexed { index, result ->
        steps.add(
            ExecutionStep(
                id = "synthetic-result-$timestamp-$index",
                type = if (result.contains("error", ignoreCase = true)) "error" else "tool_result",
                content = result.take(500),
                timestamp = timestamp + toolCalls.size + index
            )
        )
    }

    return steps
}

private fun extractSyntheticExecutionSteps(content: String): List<ExecutionStep> {
    val jsonPattern = Regex("""\{[^{}]*"(?:tool_name|tool_call_id|query|name|function)"[^{}]*\}""")
    val separatorPattern = Regex("""---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]""", RegexOption.IGNORE_CASE)

    val toolCalls = jsonPattern.findAll(content).map { it.value }.toList()
    val toolResults = separatorPattern.split(content).drop(1).map { it.trim() }

    return buildSyntheticExecutionSteps(toolCalls, toolResults)
}

private fun buildPipelineSteps(messages: List<Message>): List<PipelineStep> {
    val steps = mutableListOf<PipelineStep>()

    for (msg in messages) {
        if (msg.role == MessageRole.ASSISTANT) {
            // 1. 推理 → Thinking step
            if (!msg.reasoning.isNullOrBlank()) {
                steps.add(PipelineStep.Thinking(reasoning = msg.reasoning!!))
            }

            // 2. 工具执行 → ToolExec step
            if (!msg.executionSteps.isNullOrEmpty()) {
                steps.add(PipelineStep.ToolExec(
                    steps = msg.executionSteps!!,
                    isExecuting = false
                ))
            }

            // 3. 正文 → Content step（含工具内容嗅探）
            if (msg.content.isNotBlank()) {
                // 渲染层最后防线：对 content 做工具特征检测
                if (hasInlineToolData(msg.content)) {
                    val syntheticSteps = extractSyntheticExecutionSteps(msg.content)
                    steps.add(PipelineStep.ToolExec(
                        steps = syntheticSteps,
                        isExecuting = false
                    ))
                    // 清洗后的剩余正文（移除工具 JSON/XML 部分）
                    val cleanContent = stripToolArtifacts(msg.content)
                    if (cleanContent.isNotBlank()) {
                        steps.add(PipelineStep.Content(content = cleanContent))
                    }
                } else {
                    steps.add(PipelineStep.Content(content = msg.content))
                }
            }
        }
        // TOOL 消息的内容已经在 executionSteps 中展示，此处跳过
    }

    return steps
}

// ─────────────────────────────────────────────────────────────────
//  InlineThinkingRow — 紧凑思考指示器（气泡内联版本）
// ─────────────────────────────────────────────────────────────────

@Composable
private fun InlineThinkingRow(
    reasoning: String,
    isGenerating: Boolean,
    fontSize: Int
) {
    var internalExpanded by remember { mutableStateOf(isGenerating) }
    var collapsePending by remember { mutableStateOf(false) }

    // 联动 isGenerating 状态：生成时立即展开，生成完毕 300ms 延迟折叠
    LaunchedEffect(isGenerating) {
        when {
            isGenerating -> {
                collapsePending = false
                internalExpanded = true
            }
            else -> {
                collapsePending = true
                delay(300L)
                if (collapsePending) {
                    internalExpanded = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── 折叠行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f) // 进一步缩减指示器宽度
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexaraColors.Primary.copy(alpha = 0.08f))
                    .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable {
                        collapsePending = false
                        internalExpanded = !internalExpanded
                    } // 移到此处修复涟漪超出容器 Bug
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isGenerating) {
                    // 脉冲圆点
                    val infiniteTransition = rememberInfiniteTransition(label = "think_dot")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .alpha(dotAlpha)
                            .background(NexaraColors.Primary)
                    )
                } else {
                    Icon(
                        Icons.Rounded.CheckCircle, null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = if (isGenerating) "正在思考" else "思考完成",
                    style = NexaraTypography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = NexaraColors.Primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (internalExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = internalExpanded && reasoning.isNotBlank(),
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(200))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = NexaraColors.SurfaceLow.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    val dimmedColor = NexaraColors.OnSurfaceVariant.copy(alpha = 0.8f) // 弱化颜色
                    val targetFontSize = (fontSize + 1).coerceAtLeast(11)
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides dimmedColor,
                        androidx.compose.material3.LocalTextStyle provides NexaraTypography.bodySmall.copy(
                            fontSize = targetFontSize.sp,
                            lineHeight = (targetFontSize + 5).sp,
                            color = dimmedColor,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    ) {
                        MarkdownText(
                            markdown = reasoning,
                            isStreaming = isGenerating,
                            fontSize = targetFontSize,
                            showCursor = false,
                            overrideColor = dimmedColor,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  InlineToolRow — 紧凑工具执行指示器（气泡内联版本）
// ─────────────────────────────────────────────────────────────────

@Composable
private fun InlineToolRow(
    steps: List<ExecutionStep>,
    isExecuting: Boolean,
    fontSize: Int
) {
    var isExpanded by remember { mutableStateOf(false) } // 默认折叠

    // 分类：tool_call 和 tool_result
    val callSteps = steps.filter { it.type == "tool_call" || it.toolName != null }
    val resultSteps = steps.filter { it.type == "tool_result" || it.type == "error" }
    val hasError = steps.any { it.type == "error" }
    val toolName = steps.firstOrNull()?.toolName ?: "工具"

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f) // 进一步缩减指示器宽度
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexaraColors.Tertiary.copy(alpha = 0.08f))
                    .border(0.5.dp, NexaraColors.Tertiary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { isExpanded = !isExpanded } // 移到此处修复涟漪超出容器 Bug
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isExecuting) {
                    val infiniteTransition = rememberInfiniteTransition(label = "tool_dot")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .alpha(dotAlpha)
                            .background(NexaraColors.Tertiary)
                    )
                } else if (hasError) {
                    Icon(
                        Icons.Rounded.Cancel, null,
                        tint = NexaraColors.Error,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(
                        Icons.Rounded.CheckCircle, null,
                        tint = NexaraColors.Tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = toolName,
                    style = NexaraTypography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = NexaraColors.Tertiary
                )
                if (hasError && !isExecuting) {
                    Text(
                        text = "指令有误",
                        style = NexaraTypography.labelSmall.copy(fontSize = 10.sp),
                        color = NexaraColors.Error.copy(alpha = 0.8f)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // ── 展开内容：调用参数 + 返回结果 ──
        AnimatedVisibility(
            visible = isExpanded && steps.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                callSteps.forEach { call ->
                    if (!call.toolArgs.isNullOrBlank()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                color = NexaraColors.SurfaceLow.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp)
                            ) {
                                Text(
                                    text = "调用参数: ${call.toolArgs}",
                                    modifier = Modifier.padding(8.dp),
                                    style = NexaraTypography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
                resultSteps.forEach { result ->
                    if (!result.content.isNullOrBlank()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                color = NexaraColors.SurfaceLow.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth() // 展开内容与正文等宽
                                    .padding(bottom = 2.dp)
                            ) {
                                Text(
                                    text = result.content!!.take(300) + if (result.content!!.length > 300) "…" else "",
                                    modifier = Modifier.padding(8.dp),
                                    style = NexaraTypography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = NexaraColors.OnSurfaceVariant,
                                        lineHeight = 14.sp
                                    ),
                                    maxLines = 4
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  ContentSegment — 正文渲染
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ContentSegment(
    content: String,
    isStreaming: Boolean,
    fontSize: Int,
    onContentChange: ((String) -> Unit)?
) {
    MarkdownText(
        markdown = content,
        isStreaming = isStreaming,
        fontSize = fontSize,
        onContentChange = onContentChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

// ─────────────────────────────────────────────────────────────────
//  PipelineConnector — 竖线连接器
// ─────────────────────────────────────────────────────────────────

@Composable
private fun PipelineConnector(
    isLast: Boolean,
    withLine: Boolean = false,
    color: Color = NexaraColors.OutlineVariant.copy(alpha = 0.25f)
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(20.dp)
            .then(if (withLine) Modifier.height(IntrinsicSize.Max) else Modifier.height(24.dp))
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        if (!isLast || withLine) {
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .weight(1f)
                    .background(color)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  UserMessageBubble — 用户消息（从 ChatBubble 抽取）
// ─────────────────────────────────────────────────────────────────

@Composable
fun UserMessageBubble(
    message: Message,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val timestamp = remember(message.createdAt) { timeFormat.format(java.util.Date(message.createdAt)) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            shape = com.promenar.nexara.ui.theme.NexaraCustomShapes.ChatBubbleUser,
            color = NexaraColors.SurfaceHigh,
            border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column {
                if (!message.userImages.isNullOrEmpty()) {
                    Column(
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        message.userImages!!.forEach { dataUrl ->
                            coil3.compose.AsyncImage(
                                model = dataUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        style = NexaraTypography.bodyMedium.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.5).sp
                        ),
                        color = NexaraColors.OnBackground,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        Text(
            text = timestamp,
            style = NexaraTypography.labelSmall.copy(
                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp
            ),
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  StreamingCursor — 生成中的闪烁光标
// ─────────────────────────────────────────────────────────────────

@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp) // 确保在首字生成前有足够的占位高度
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 移除 PipelineConnector 以修复光标偏右 Bug，使其与正文左对齐
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(20.dp)
                .alpha(alpha)
                .background(NexaraColors.Primary, RoundedCornerShape(2.dp))
        )
    }
}
