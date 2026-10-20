package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.workspace.isWorkspaceSwitchNotice
import androidx.compose.animation.*
import com.promenar.nexara.ui.theme.forReading
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.promenar.nexara.ui.common.NexaraMetaSeparator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentCodec
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentItem
import com.promenar.nexara.data.local.db.recovery.LegacyAttachmentOpenResult
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.ui.renderer.ImageLightbox
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val isUser: Boolean,
    /** 工作区切换提示：独立成组，居中显示为一行说明。 */
    val isNotice: Boolean = false,
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
        if (msg.isWorkspaceSwitchNotice) {
            groups.add(PipelineGroup(listOf(msg), isUser = false, isNotice = true))
            i++
        } else if (msg.role == MessageRole.USER) {
            // USER 消息：每个独立成组（与后续 AI 回复分离以保持锚定语义）
            groups.add(PipelineGroup(listOf(msg), isUser = true))
            i++
        } else {
            // ASSISTANT / TOOL：视作同一轮 AI 响应，全部合并到一组
            val groupMsgs = mutableListOf<Message>()
            while (i < messages.size && messages[i].role != MessageRole.USER && !messages[i].isWorkspaceSwitchNotice) {
                val candidate = messages[i]
                val candidateIsError = candidate.isError || candidate.errorMessage != null
                if (candidateIsError && groupMsgs.isNotEmpty()) break
                groupMsgs.add(candidate)
                i++
                if (candidateIsError) break
            }
            groups.add(PipelineGroup(groupMsgs, isUser = false))
        }
    }
    return groups
}

internal fun messageCopyOverride(
    onCopy: ((String) -> Unit)?,
    content: String
): (() -> Unit)? = onCopy?.let { copyHandler ->
    { copyHandler(content) }
}

internal fun pipelineReplyCopyText(steps: List<PipelineStep>): String =
    steps.filterIsInstance<PipelineStep.Content>().map { it.content }.filter(String::isNotBlank).joinToString("\n\n")

internal fun historicalGenerationFailureNotice(errorMessage: String?): UiStatusNotice =
    GenerationFailureNotice.fromPersisted(errorMessage)

internal fun streamingReasoningPreview(reasoning: String, maxChars: Int = 180): String =
    reasoning.takeLast(maxChars.coerceAtLeast(1))
        .dropWhile { it.isLowSurrogate() }
        .replace(Regex("\\s+"), " ")
        .trim()

// ─────────────────────────────────────────────────────────────────
//  PipelineBubble — 单轮 AI 对话的线性格局
// ─────────────────────────────────────────────────────────────────

/**
 * 将一轮助手响应渲染为通栏内容，步骤相邻排列，正文边界保留阅读间距。
 * 检索、思考与工具详情由对应步骤按需展开。
 */
@Composable
fun PipelineBubble(
    group: PipelineGroup,
    isGenerating: Boolean,
    status: GenerationStatus = GenerationStatus.IDLE,
    streamingContent: String,
    fontSize: Int,
    modelDisplayNames: Map<String, String> = emptyMap(),
    onContentChange: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onRegenerate: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasRagContent: Boolean = false,
    onMessageContentChange: ((String, String) -> Unit)? = null,
    ragContent: @Composable () -> Unit = {},
) {
    if (group.isNotice) {
        WorkspaceSwitchNoticeRow(text = group.messages.first().content, modifier = modifier)
        return
    }
    if (group.isUser) {
        val msg = group.messages.first()
        UserMessageBubble(
            message = msg,
            fontSize = fontSize,
            modifier = modifier,
            onDelete = { onDelete?.invoke(msg.id) },
            onCopy = messageCopyOverride(onCopy, msg.content),
            onRegenerate = onRegenerate?.let { handler -> { handler(msg.id) } },
            onBranch = onBranch?.let { handler -> { handler(msg.id) } },
        )
        return
    }

    val renderPlan = remember(group.messages, isGenerating, streamingContent.isNotEmpty()) {
        buildPipelineRenderPlan(
            messages = group.messages,
            isGenerating = isGenerating,
            hasStreamingContent = streamingContent.isNotEmpty()
        )
    }
    val allSteps = renderPlan.steps

    val footerContent = allSteps.filterIsInstance<PipelineStep.Content>().lastOrNull { it.content.isNotBlank() }
    val replyCopyText = remember(allSteps) { pipelineReplyCopyText(allSteps) }
    Column(modifier = modifier.fillMaxWidth()) {
        ragContent()
        allSteps.forEachIndexed { index, step ->
            val isLastInGroup = index == allSteps.lastIndex
            val followsContent = allSteps.getOrNull(index - 1) is PipelineStep.Content
            if ((index > 0 && (step is PipelineStep.Content || followsContent)) ||
                (index == 0 && hasRagContent && step is PipelineStep.Content)
            ) {
                Spacer(Modifier.height(8.dp))
            }
            key(when (step) {
                is PipelineStep.Thinking -> "thinking:${step.messageId}"
                is PipelineStep.ToolExec -> "tools:${step.steps.firstOrNull()?.id}"
                is PipelineStep.Content -> "content:$index"
            }) {
                when (step) {
                    is PipelineStep.Thinking -> ThinkingTrace(
                        reasoning = step.reasoning,
                        isGenerating = isGenerating && isLastInGroup && streamingContent.isEmpty(),
                        fontSize = fontSize,
                    )
                    is PipelineStep.ToolExec -> InlineToolRow(
                        steps = step.steps, isExecuting = step.isExecuting, fontSize = fontSize,
                    )
                    is PipelineStep.Content -> {
                        val displayContent = if (isLastInGroup && isGenerating) {
                            streamingContent.ifEmpty { step.content }
                        } else step.content
                        val sourceMessage = pipelineContentOwner(group.messages, step)
                        ContentSegment(
                            content = displayContent,
                            isStreaming = isLastInGroup && isGenerating,
                            showStreamingCursor = renderPlan.showContentCursor,
                            fontSize = fontSize,
                            onContentChange = sourceMessage?.let { msg ->
                                onMessageContentChange?.let { handler -> { content: String -> handler(msg.id, content) } }
                            } ?: onContentChange,
                            onCopy = messageCopyOverride(onCopy, displayContent),
                            onRegenerate = sourceMessage?.let { msg -> onRegenerate?.let { handler -> { handler(msg.id) } } },
                            onDelete = sourceMessage?.let { msg -> onDelete?.let { handler -> { handler(msg.id) } } },
                            onBranch = sourceMessage?.let { msg -> onBranch?.let { handler -> { handler(msg.id) } } },
                        )
                    }
                }
            }
        }
        if (renderPlan.showStandaloneCursor) StreamingCursor()
        if (footerContent != null) {
            pipelineContentOwner(group.messages, footerContent)?.let { lastMsg ->
                val context = LocalContext.current
                val timestamp = remember(lastMsg.createdAt) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(lastMsg.createdAt))
                }
                val modelDisplayName = if (lastMsg.modelId.isNullOrBlank()) "" else {
                    modelDisplayNames[lastMsg.modelId]?.takeIf(String::isNotBlank)
                        ?: lastMsg.modelId.substringAfter("::", lastMsg.modelId)
                }
                AssistantActionRow(
                    modelDisplayName = modelDisplayName,
                    timestamp = timestamp,
                    isGenerating = isGenerating,
                    onCopy = { onCopy?.invoke(replyCopyText) ?: copyToClipboard(context, replyCopyText) },
                    onRegenerate = onRegenerate?.let { handler -> { handler(lastMsg.id) } },
                    onBranch = onBranch?.let { handler -> { handler(lastMsg.id) } },
                    onDelete = onDelete?.let { handler -> { handler(lastMsg.id) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    // ── 错误信息 ──
    group.messages.lastOrNull()?.let { lastMsg ->
        if (lastMsg.isError || lastMsg.errorMessage != null) {
            val resolved = remember(lastMsg.errorMessage) {
                GenerationFailureNotice.template(
                    historicalGenerationFailureNotice(lastMsg.errorMessage),
                )
            }
            ErrorMessageSegment(
                messageId = lastMsg.id,
                text = stringResource(resolved.resourceId, *resolved.args.toTypedArray()),
                fontSize = fontSize,
                onDelete = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorMessageSegment(
    messageId: String,
    text: String,
    fontSize: Int,
    onDelete: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = NexaraSpacing.Small)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true }),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .padding(horizontal = NexaraSpacing.Large, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = text,
                    style = NexaraTypography.bodyMedium.copy(
                        fontSize = (fontSize - 2).coerceAtLeast(10).sp,
                    ),
                )
            }
        }
        MessageContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onCopy = { copyToClipboard(context, text) },
            onDelete = {
                onDelete?.invoke(messageId)
                showMenu = false
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Pipeline 步骤构建
// ─────────────────────────────────────────────────────────────────

internal sealed class PipelineStep {
    data class Thinking(
        val reasoning: String,
        val messageId: String = "",
    ) : PipelineStep()

    data class ToolExec(
        val steps: List<ExecutionStep>,
        val isExecuting: Boolean
    ) : PipelineStep()

    data class Content(
        val content: String,
        val messageId: String = "",
    ) : PipelineStep()
}

/** 正文的展示来源由消息 ID 定位，缺失来源时不猜测其它消息。 */
internal fun pipelineContentOwner(messages: List<Message>, content: PipelineStep.Content): Message? =
    messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.id == content.messageId }

internal data class PipelineRenderPlan(
    val steps: List<PipelineStep>,
    val showStandaloneCursor: Boolean,
    val showContentCursor: Boolean
)

internal fun buildPipelineRenderPlan(
    messages: List<Message>,
    isGenerating: Boolean,
    hasStreamingContent: Boolean
): PipelineRenderPlan {
    val finalAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val steps = buildPipelineSteps(messages).map { step ->
        if (step is PipelineStep.ToolExec) step.copy(
            isExecuting = isGenerating && finalAssistant != null && toolInvocations(step.steps).any { invocation ->
                !invocation.complete && (invocation.call?.timestamp ?: 0) > 0 &&
                    invocation.call?.id?.startsWith("${finalAssistant.id}::") == true
            },
        ) else step
    }.toMutableList()
    val finalAssistantIsPlaceholder = finalAssistant != null && finalAssistant.content.isBlank()

    if (isGenerating && hasStreamingContent && finalAssistantIsPlaceholder) {
        steps.add(PipelineStep.Content(content = "", messageId = requireNotNull(finalAssistant).id))
    }

    return PipelineRenderPlan(
        steps = steps,
        showStandaloneCursor = isGenerating && steps.none { it is PipelineStep.Content },
        showContentCursor = false
    )
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
    val identity = "${toolCalls.hashCode()}-${toolResults.hashCode()}"

    toolCalls.forEachIndexed { index, args ->
        val toolName = inferredToolName(ExecutionStep("source", "tool_call", toolArgs = args, timestamp = 0))

        steps.add(
            ExecutionStep(
                id = "synthetic-tool-$identity-$index",
                type = "tool_call",
                toolName = toolName,
                toolArgs = args,
                timestamp = 0
            )
        )
    }

    toolResults.forEachIndexed { index, result ->
        steps.add(
            ExecutionStep(
                id = "synthetic-result-$identity-$index",
                type = if (result.contains("error", ignoreCase = true)) "error" else "tool_result",
                content = result,
                timestamp = 0
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

/** 消息只在展示副本中限定作用域，避免跨工具轮次复用调用 ID 时混淆真实调用。 */
private fun scopedToolSteps(messageId: String, steps: List<ExecutionStep>): List<ExecutionStep> =
    toolInvocations(steps).flatMapIndexed { index, invocation ->
        invocation.steps.map { step ->
            step.copy(id = "$messageId::${step.id}", toolCallId = "$messageId::invocation-$index")
        }
    }

private fun buildPipelineSteps(messages: List<Message>): List<PipelineStep> {
    val steps = mutableListOf<PipelineStep>()
    for ((messageIndex, msg) in messages.withIndex()) {
        val knownToolNames = buildMap {
            messages.subList(messageIndex + 1, messages.size).takeWhile { it.role == MessageRole.TOOL }.forEach { tool ->
                if (!tool.toolCallId.isNullOrBlank() && !tool.name.isNullOrBlank()) put(tool.toolCallId, tool.name)
            }
            msg.executionSteps.orEmpty().forEach { execution ->
                if (!execution.toolCallId.isNullOrBlank()) inferredToolName(execution)?.let { put(execution.toolCallId, it) }
            }
            msg.toolCalls.orEmpty().forEach { put(it.id, it.name) }
        }
        if (msg.role == MessageRole.ASSISTANT) {
            // 1. 推理 → Thinking step
            if (!msg.reasoning.isNullOrBlank()) {
                steps.add(PipelineStep.Thinking(reasoning = msg.reasoning!!, messageId = msg.id))
            }

            // 2. 工具执行 → ToolExec step
            if (!msg.executionSteps.isNullOrEmpty()) {
                val namedSteps = msg.executionSteps!!.map { execution ->
                    execution.copy(toolName = execution.toolName?.takeIf(String::isNotBlank)
                        ?: knownToolNames[execution.toolCallId] ?: inferredToolName(execution))
                }
                val scoped = scopedToolSteps(msg.id, namedSteps)
                if (scoped.isNotEmpty()) steps.add(PipelineStep.ToolExec(steps = scoped, isExecuting = false))
            }

            // 3. 正文 → Content step（含工具内容嗅探）
            if (msg.content.isNotBlank()) {
                // 渲染层最后防线：对 content 做工具特征检测
                if (hasInlineToolData(msg.content)) {
                    // 结构化执行事件是权威来源；正文嗅探仅兼容缺少执行事件的历史消息。
                    val hasStructuredTools = msg.executionSteps.orEmpty().any {
                        it.type == "tool_call" || it.type == "tool_result" || it.type == "error"
                    }
                    if (!hasStructuredTools) {
                        val syntheticSteps = scopedToolSteps(msg.id, extractSyntheticExecutionSteps(msg.content))
                        if (syntheticSteps.isNotEmpty()) steps.add(PipelineStep.ToolExec(
                            steps = syntheticSteps,
                            isExecuting = false,
                        ))
                    }
                    // 清洗后的剩余正文（移除工具 JSON/XML 部分）
                    val cleanContent = stripToolArtifacts(msg.content)
                    if (cleanContent.isNotBlank()) {
                        steps.add(PipelineStep.Content(content = cleanContent, messageId = msg.id))
                    }
                } else {
                    steps.add(PipelineStep.Content(content = msg.content, messageId = msg.id))
                }
            }
        }
        // TOOL 消息的内容已经在 executionSteps 中展示，此处跳过
    }

    val merged = mutableListOf<PipelineStep>()
    steps.forEach { step ->
        val previous = merged.lastOrNull()
        if (step is PipelineStep.ToolExec && previous is PipelineStep.ToolExec) {
            merged[merged.lastIndex] = previous.copy(steps = previous.steps + step.steps)
        } else merged.add(step)
    }
    return merged
}

// ─────────────────────────────────────────────────────────────────
//  ThinkingTrace — 紧凑思考指示器（气泡内联版本）
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun ThinkingTrace(
    reasoning: String,
    isGenerating: Boolean,
    fontSize: Int,
    modifier: Modifier = Modifier,
    elapsedMs: Long? = null,
    initiallyExpanded: Boolean = false,
) {
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var measuredElapsed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(isGenerating) {
        if (isGenerating) {
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                while (true) {
                    measuredElapsed = android.os.SystemClock.elapsedRealtime() - start
                    delay(200L)
                }
            } finally {
                measuredElapsed = android.os.SystemClock.elapsedRealtime() - start
            }
        }
    }
    val seconds = (elapsedMs ?: measuredElapsed)?.coerceAtLeast(0)?.div(1_000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val summary = when {
        isGenerating -> pluralStringResource(R.plurals.chat_step_thinking, seconds ?: 0, seconds ?: 0)
        seconds != null -> pluralStringResource(R.plurals.chat_step_thought_duration, seconds, seconds)
        else -> stringResource(R.string.chat_step_thought)
    }
    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.CHAT_THINKING_TRACE)) {
        CompactStepRow(
            segments = listOf(summary),
            icon = Icons.Rounded.CheckCircle,
            active = isGenerating,
            fontSize = fontSize,
            expanded = internalExpanded,
            state = stringResource(if (internalExpanded) R.string.common_state_expanded else R.string.common_state_collapsed),
            tag = UiTags.CHAT_THINKING_TOGGLE,
            clickLabel = stringResource(if (internalExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            onClick = { internalExpanded = !internalExpanded },
            preview = if (isGenerating) {{
                val tail = remember(reasoning) { streamingReasoningPreview(reasoning) }
                val scroll = rememberScrollState(initial = Int.MAX_VALUE)
                LaunchedEffect(tail) {
                    snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
                }
                Box(modifier = Modifier.weight(1f).fadingReasoningTail(enabled = scroll.maxValue > 0).testTag("CHAT_THINKING_PREVIEW")) {
                    Text(
                        text = tail,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.horizontalScroll(scroll, enabled = false),
                        style = NexaraTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }} else null,
        )
        if (internalExpanded && reasoning.isNotBlank()) {
            val ruleColor = MaterialTheme.colorScheme.outlineVariant
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag(UiTags.CHAT_THINKING_CONTENT)) {
                Box(Modifier.matchParentSize()) {
                    Spacer(Modifier.width(2.dp).fillMaxHeight().background(ruleColor))
                }
                MarkdownText(
                    markdown = reasoning,
                    fontSize = NexaraTypography.bodySmall.fontSize.value.toInt(),
                    overrideColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showCursor = false,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                )
            }
        }
    }
}

/** 可见操作行 36dp，按钮使用独立的 48dp 触控区域。 */
@Composable
internal fun AssistantActionRow(
    modelDisplayName: String,
    timestamp: String,
    isGenerating: Boolean,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (isGenerating) return
    var showMore by remember { mutableStateOf(false) }
    val metaStyle = NexaraTypography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(modifier = modifier.fillMaxWidth().height(36.dp).testTag("CHAT_ASSISTANT_ACTIONS")) {
        Row(
            modifier = Modifier.align(Alignment.Center).layout { measurable, constraints ->
                val bleed = 17.5.dp.roundToPx()
                val row = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + bleed, maxWidth = constraints.maxWidth + bleed))
                layout(constraints.maxWidth, row.height) { row.placeRelative(-bleed, 0) }
            }.fillMaxWidth().wrapContentHeight(unbounded = true).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCopy, modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_COPY")) {
                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.chat_action_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if (onRegenerate != null) {
                IconButton(onClick = onRegenerate, modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_REGENERATE")) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_action_regenerate), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Box {
                IconButton(
                    onClick = { showMore = true },
                    enabled = onBranch != null || onDelete != null,
                    modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_MORE"),
                ) {
                    Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.chat_step_more_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                NexaraDropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    if (onBranch != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_branch)) },
                            leadingIcon = { Icon(Icons.Rounded.ForkRight, null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_BRANCH),
                            onClick = { onBranch(); showMore = false },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                            onClick = { onDelete(); showMore = false },
                        )
                    }
                }
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (modelDisplayName.isNotBlank()) {
                    Text(
                        text = modelDisplayName,
                        style = metaStyle,
                        modifier = Modifier.weight(1f, fill = false).testTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(NexaraMetaSeparator, style = metaStyle, modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics {})
                }
                Text(timestamp, style = metaStyle, modifier = Modifier.testTag(UiTags.CHAT_ASSISTANT_TIME_METADATA), maxLines = 1)
            }
        }
    }
}

@Composable
private fun toolSummaryCompletedText(part: ToolSummaryPart): String {
    val resId = when (part.action) {
        ToolSummaryAction.COMMAND -> R.plurals.chat_step_command_done
        ToolSummaryAction.WEB_SEARCH -> if (part.count == 1) R.plurals.chat_step_web_search_once else R.plurals.chat_step_web_search_done
        ToolSummaryAction.WEB_READ -> R.plurals.chat_step_web_read_done
        ToolSummaryAction.FILE_READ -> R.plurals.chat_step_file_read_done
        ToolSummaryAction.FILE_EDIT -> R.plurals.chat_step_file_edit_done
        ToolSummaryAction.KNOWLEDGE_SEARCH -> R.plurals.chat_step_knowledge_done
        ToolSummaryAction.MEMORY_SEARCH -> R.plurals.chat_step_memory_done
        ToolSummaryAction.IMAGE -> R.plurals.chat_step_image_done
        ToolSummaryAction.PLAN -> R.plurals.chat_step_plan_done
        ToolSummaryAction.TIME -> R.plurals.chat_step_time_done
        ToolSummaryAction.CALCULATE -> R.plurals.chat_step_calculate_done
        ToolSummaryAction.SKILL -> R.plurals.chat_step_skill_done
        ToolSummaryAction.OTHER -> R.plurals.chat_step_other_done
    }
    return pluralStringResource(resId, part.count, part.count)
}

@Composable
internal fun toolFriendlyName(toolName: String?): String = stringResource(when (toolSummaryAction(toolName)) {
    ToolSummaryAction.COMMAND -> R.string.chat_step_command_name
    ToolSummaryAction.WEB_SEARCH -> R.string.chat_step_web_search_name
    ToolSummaryAction.WEB_READ -> R.string.chat_step_web_read_name
    ToolSummaryAction.FILE_READ -> R.string.chat_step_file_read_name
    ToolSummaryAction.FILE_EDIT -> R.string.chat_step_file_edit_name
    ToolSummaryAction.KNOWLEDGE_SEARCH -> R.string.chat_step_knowledge_name
    ToolSummaryAction.MEMORY_SEARCH -> R.string.chat_step_memory_name
    ToolSummaryAction.IMAGE -> R.string.chat_step_image_name
    ToolSummaryAction.PLAN -> R.string.chat_step_plan_name
    ToolSummaryAction.TIME -> R.string.chat_step_time_name
    ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_name
    ToolSummaryAction.SKILL -> R.string.chat_step_skill_name
    ToolSummaryAction.OTHER -> R.string.chat_step_other_name
})

@Composable
internal fun toolGroupSummaryText(summary: ToolGroupSummary): String {
    if (summary.status == ToolSummaryStatus.PENDING) {
        return pluralStringResource(R.plurals.chat_step_pending_count, summary.pendingCount, summary.pendingCount)
    }
    summary.runningAction?.let { action ->
        return stringResource(when (action) {
            ToolSummaryAction.COMMAND -> R.string.chat_step_command_running
            ToolSummaryAction.WEB_SEARCH -> R.string.chat_step_web_search_running
            ToolSummaryAction.WEB_READ -> R.string.chat_step_web_read_running
            ToolSummaryAction.FILE_READ -> R.string.chat_step_file_read_running
            ToolSummaryAction.FILE_EDIT -> R.string.chat_step_file_edit_running
            ToolSummaryAction.KNOWLEDGE_SEARCH -> R.string.chat_step_knowledge_running
            ToolSummaryAction.MEMORY_SEARCH -> R.string.chat_step_memory_running
            ToolSummaryAction.IMAGE -> R.string.chat_step_image_running
            ToolSummaryAction.PLAN -> R.string.chat_step_plan_running
            ToolSummaryAction.TIME -> R.string.chat_step_time_running
            ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_running
            ToolSummaryAction.SKILL -> R.string.chat_step_skill_running
            ToolSummaryAction.OTHER -> R.string.chat_step_other_running
        })
    }
    if (summary.useGenericSummary || summary.parts.isEmpty()) {
        return pluralStringResource(R.plurals.chat_step_other_done, summary.count, summary.count)
    }
    val separator = stringResource(R.string.chat_step_summary_separator)
    return summary.parts.map { toolSummaryCompletedText(it) }.joinToString(separator)
}

@Composable
private fun stepDurationText(durationMs: Long): String = if (durationMs < 1_000) {
    pluralStringResource(R.plurals.chat_step_duration_ms, durationMs.toInt(), durationMs)
} else {
    pluralStringResource(
        R.plurals.chat_step_duration_seconds,
        (durationMs / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1_000.0),
    )
}

// ─────────────────────────────────────────────────────────────────
//  InlineToolRow — 工具摘要与按需展开的调用详情
// ─────────────────────────────────────────────────────────────────

/** 步骤展开内容的底部留白。 */
internal val ExpandedStepBottomPadding = 8.dp

@Composable
internal fun InlineToolRow(
    steps: List<ExecutionStep>,
    isExecuting: Boolean,
    fontSize: Int,
    initiallyExpanded: Boolean = false,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val invocations = remember(steps) { toolInvocations(steps) }
    val summary = remember(invocations, isExecuting) { toolGroupDisplaySummary(invocations, isExecuting) }
    val fileChanges = remember(invocations) { toolGroupFileChanges(invocations) }
    var openedChange by remember { mutableStateOf<com.promenar.nexara.data.model.FileChangeSummary?>(null) }
    val showFileTitle = fileChanges.isNotEmpty() && summary.runningAction == null && summary.status != ToolSummaryStatus.PENDING
    val segments = buildList<CharSequence> {
        // 文件改动只来自成功的文件工具：全是文件编辑时标题直接给出文件与行数，混合工具时作为补充段。
        if (!showFileTitle || !summary.isFileEditOnly) add(toolGroupSummaryText(summary))
        if (showFileTitle) add(com.promenar.nexara.ui.chat.diff.fileChangesStyledTitle(fileChanges))
        if (summary.failedCount == 0 && summary.runningAction == null) summary.durationMs?.let { add(stepDurationText(it)) }
    }
    openedChange?.let { change ->
        com.promenar.nexara.ui.chat.diff.FileChangeDiffSheet(change, onDismissRequest = { openedChange = null })
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        CompactStepRow(
            segments = segments,
            icon = Icons.Rounded.CheckCircle,
            active = summary.status == ToolSummaryStatus.RUNNING,
            fontSize = fontSize,
            expanded = isExpanded,
            state = stringResource(if (isExpanded) R.string.common_state_expanded else R.string.common_state_collapsed),
            clickLabel = stringResource(if (isExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            tag = "CHAT_TOOL_TOGGLE",
            failureText = if (summary.failedCount > 0) pluralStringResource(R.plurals.chat_step_failed_count, summary.failedCount, summary.failedCount) else null,
            onClick = { isExpanded = !isExpanded },
        )
        if (isExpanded) {
            // 展开内容底部 8dp，与流水线块间 8dp 合计 16dp，与段落间距一致。
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag("CHAT_TOOL_DETAILS"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                invocations.forEach { invocation ->
                    key(invocation.steps.firstOrNull()?.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(toolFriendlyName(invocation.name), style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                invocation.name?.let { name ->
                                    Text(
                                        name,
                                        style = NexaraTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                invocation.durationMs?.let { Text(stepDurationText(it), style = NexaraTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            val invocationChanges = remember(invocation) { invocation.fileChanges }
                            if (invocationChanges.isNotEmpty()) {
                                com.promenar.nexara.ui.chat.diff.FileChangeList(invocationChanges, onOpen = { openedChange = it })
                            }
                            val workspaceChanges = remember(invocation) { invocation.workspaceChanges }
                            workspaceChanges?.let { com.promenar.nexara.ui.chat.diff.WorkspaceChangesList(it) }
                            invocation.call?.toolArgs?.takeIf(String::isNotBlank)?.let { args ->
                                ExpandableToolText(text = args, collapsedLines = 6, arguments = true)
                            }
                            invocation.results.forEach { result ->
                                val images = remember(result.data) { extractToolImageModels(result.data) }
                                images.take(4).forEach { imageModel ->
                                    ChatImageThumbnail(
                                        imageModel = imageModel,
                                        contentDescription = stringResource(R.string.chat_cd_tool_image),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).clip(MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                result.content?.takeIf(String::isNotBlank)?.let { content ->
                                    ExpandableToolText(text = content, collapsedLines = 12, error = result.type == "error")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExpandableToolText(
    text: String,
    collapsedLines: Int,
    arguments: Boolean = false,
    error: Boolean = false,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val explicitLineOverflow = remember(text, collapsedLines) {
        text.lineSequence().take(collapsedLines + 1).count() > collapsedLines
    }
    var overflows by remember(text, collapsedLines) { mutableStateOf(explicitLineOverflow) }
    Column(
        modifier = if (arguments) Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(10.dp)
        else Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = if (arguments) NexaraTypography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                else NexaraTypography.bodySmall.forReading(),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = explicitLineOverflow || it.hasVisualOverflow },
            modifier = Modifier.testTag(if (arguments) "CHAT_TOOL_ARGUMENTS" else "CHAT_TOOL_RESULT"),
        )
        if (overflows || expanded) {
            ChatExpandLink(
                text = stringResource(if (expanded) R.string.chat_step_collapse_all else R.string.chat_step_expand_all),
                onClick = { expanded = !expanded }, modifier = Modifier.testTag("CHAT_TOOL_EXPAND_ALL"),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  ContentSegment — 正文渲染
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContentSegment(
    content: String,
    isStreaming: Boolean,
    showStreamingCursor: Boolean,
    fontSize: Int,
    onContentChange: ((String) -> Unit)?,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }

    Box {
        Surface(
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("CHAT_ASSISTANT_BODY")
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        pressOffset = DpOffset.Zero
                        showMenu = true
                    }
                )
        ) {
            MarkdownText(
                markdown = content,
                isStreaming = isStreaming,
                showCursor = showStreamingCursor,
                fontSize = fontSize,
                onContentChange = onContentChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        MessageContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onCopy = {
                onCopy?.invoke() ?: copyToClipboard(context, content)
                showMenu = false
            },
            onRegenerate = onRegenerate,
            onBranch = onBranch,
            onDelete = {
                onDelete?.invoke()
                showMenu = false
            },
            offset = pressOffset
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  工具图片结果解析
// ─────────────────────────────────────────────────────────────────

private fun extractToolImageModels(data: String?): List<String> {
    if (data.isNullOrBlank()) return emptyList()
    val raw = data.trim()
    if (raw.startsWith("data:image/") || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("/")) {
        return listOf(raw)
    }

    fun JSONObject.imageModel(): String? {
        val localPath = optString("localPath").takeIf { it.isNotBlank() && it != "null" }
        val url = optString("url").takeIf { it.isNotBlank() && it != "null" }
        val b64 = optString("b64Json").takeIf { it.isNotBlank() && it != "null" }
        return localPath ?: url ?: b64?.let { "data:image/png;base64,$it" }
    }

    return runCatching {
        if (raw.startsWith("[")) {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.imageModel()
            }
        } else {
            listOfNotNull(JSONObject(raw).imageModel())
        }
    }.getOrDefault(emptyList())
}

// ─────────────────────────────────────────────────────────────────
//  UserMessageBubble — 用户消息（从 ChatBubble 抽取）
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
    message: Message,
    fontSize: Int,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val timestamp = remember(message.createdAt) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt))
    }
    val legacyAttachments by produceState(
        initialValue = emptyList(), key1 = message.legacyAttachmentsPayload, key2 = message.userImages,
    ) {
        value = withContext(Dispatchers.Default) {
            LegacyAttachmentCodec.parse(message.legacyAttachmentsPayload).filterNot { item ->
                item.type == "IMAGE" && item.uri in message.userImages.orEmpty()
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.82f
        Box(modifier = Modifier.align(Alignment.TopEnd).widthIn(max = bubbleMaxWidth)) {
            Column(
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true }),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!message.userDocuments.isNullOrEmpty()) MessageDocumentSummary(message.userDocuments.orEmpty())
                if (!message.userImages.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier.widthIn(max = bubbleMaxWidth).horizontalScroll(rememberScrollState()).testTag("CHAT_USER_IMAGES"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.userImages.orEmpty().forEach { dataUrl ->
                            ChatImageThumbnail(
                                imageModel = dataUrl,
                                contentDescription = stringResource(R.string.chat_cd_attached_image),
                                modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                if (legacyAttachments.isNotEmpty()) LegacyAttachmentSummary(legacyAttachments)
                if (message.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = bubbleMaxWidth).testTag("CHAT_USER_BUBBLE"),
                    ) {
                        Text(
                            text = message.content,
                            style = NexaraTypography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp).forReading(),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            MessageContextMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onCopy = { onCopy?.invoke() ?: copyToClipboard(context, message.content); showMenu = false },
                onRegenerate = onRegenerate,
                onBranch = onBranch,
                onDelete = { onDelete?.invoke(); showMenu = false },
                isUser = true,
                timestamp = timestamp,
            )
        }
    }
}

@Composable
private fun LegacyAttachmentSummary(items: List<LegacyAttachmentItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .testTag(UiTags.CHAT_LEGACY_ATTACHMENTS),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(stringResource(R.string.chat_legacy_attachments_title), style = NexaraTypography.labelMedium)
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CHAT_LEGACY_ATTACHMENT_ENTRY)
                    .clickable {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { LegacyAttachmentCodec.open(context, item) }
                            when (result) {
                                is LegacyAttachmentOpenResult.Ready -> runCatching { context.startActivity(result.intent) }
                                    .onFailure {
                                        Toast.makeText(context, R.string.chat_legacy_attachment_unreadable, Toast.LENGTH_SHORT).show()
                                    }
                                is LegacyAttachmentOpenResult.Unreadable ->
                                    Toast.makeText(context, R.string.chat_legacy_attachment_unreadable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(vertical = NexaraSpacing.XSmall),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.fileName, style = NexaraTypography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.unreadableReason == null) item.mimeType else stringResource(R.string.chat_legacy_attachment_unreadable),
                        style = NexaraTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatImageThumbnail(
    imageModel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var showLightbox by remember(imageModel) { mutableStateOf(false) }
    coil3.compose.AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier.clickable { showLightbox = true },
        contentScale = contentScale,
    )
    if (showLightbox) {
        ImageLightbox(imageUrl = imageModel, onDismiss = { showLightbox = false })
    }
}

@Composable
private fun MessageDocumentSummary(documents: List<MessageDocumentAttachment>) {
    Column(
        modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_DOCUMENTS),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        documents.forEach { document ->
            Row(
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(document.name, style = NexaraTypography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatMessageDocumentBytes(document.sizeBytes), style = NexaraTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatMessageDocumentBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

// ─────────────────────────────────────────────────────────────────
//  StreamingCursor — 生成中的闪烁光标
// ─────────────────────────────────────────────────────────────────

@Composable
private fun StreamingCursor() {
    val generatingDescription = stringResource(R.string.chat_cd_generating_response)
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
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                .semantics { contentDescription = generatingDescription }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  MessageContextMenu — 原生 MD3 消息上下文菜单
// ─────────────────────────────────────────────────────────────────

@Composable
fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onDelete: () -> Unit,
    isUser: Boolean = false,
    offset: DpOffset = DpOffset.Zero,
    timestamp: String? = null,
) {
    NexaraDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
    ) {
        if (timestamp != null) {
            DropdownMenuItem(text = { Text(timestamp, style = NexaraTypography.labelSmall) }, onClick = {}, enabled = false)
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { Text(stringResource(R.string.chat_action_copy), style = NexaraTypography.labelLarge) },
            onClick = {
                onCopy()
                onDismiss()
            }
        )
        
        if (onRegenerate != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { 
                    Text(
                        text = if (isUser) {
                            stringResource(R.string.chat_action_resend)
                        } else {
                            stringResource(R.string.chat_action_regenerate)
                        },
                        style = NexaraTypography.labelLarge,
                    ) 
                },
                onClick = {
                    onRegenerate()
                    onDismiss()
                }
            )
        }

        if (onBranch != null) {
            DropdownMenuItem(
                modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_BRANCH),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.ForkRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.chat_action_branch),
                        style = NexaraTypography.labelLarge,
                    )
                },
                onClick = {
                    onBranch()
                    onDismiss()
                },
            )
        }

        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { 
                Text(
                    text = stringResource(R.string.chat_action_delete),
                    style = NexaraTypography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                ) 
            },
            onClick = {
                onDelete()
                onDismiss()
            }
        )
    }
}

fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("NexaraMessage", text)
    clipboard.setPrimaryClip(clip)
}

/** 工作区切换提示：居中的一行次要文字，不带操作菜单。 */
@Composable
internal fun WorkspaceSwitchNoticeRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    )
}
