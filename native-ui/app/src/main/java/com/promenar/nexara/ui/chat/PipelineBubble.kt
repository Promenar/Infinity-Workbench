package com.promenar.nexara.ui.chat

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.promenar.nexara.ui.common.NexaraMetaLine
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import com.promenar.nexara.ui.theme.NexaraCustomShapes
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
 * 将一组 ASSISTANT+TOOL 消息渲染为单一气泡，
 * 检索、思考、工具与正文共享同一垂直间距，详情由各步骤按需展开。
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
    ragContent: @Composable () -> Unit = {},
) {
    if (group.isUser) {
        // USER 消息保持原样
        val msg = group.messages.first()
        UserMessageBubble(
            message = msg,
            fontSize = fontSize,
            onDelete = { onDelete?.invoke(msg.id) },
            onCopy = messageCopyOverride(onCopy, msg.content),
            onRegenerate = { onRegenerate?.invoke(msg.id) },
            onBranch = { onBranch?.invoke(msg.id) },
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

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(ChatStepSpacing)
        ) {
            ragContent()
            allSteps.forEachIndexed { index, step ->
                val isLastInGroup = index == allSteps.lastIndex
                
                key(when (step) {
                    is PipelineStep.Thinking -> "thinking:${step.messageId}"
                    is PipelineStep.ToolExec -> "tools:${step.steps.firstOrNull()?.id}"
                    is PipelineStep.Content -> "content:$index"
                }) {
                    when (step) {
                        is PipelineStep.Thinking -> {
                            val isThinkingStreaming = isGenerating && isLastInGroup && streamingContent.isEmpty()

                            ThinkingTrace(
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
                            val lastMsg = group.assistantMessages.lastOrNull()
                            val copySource = lastMsg?.content ?: displayContent
                            ContentSegment(
                                content = displayContent,
                                isStreaming = isLastInGroup && isGenerating,
                                showStreamingCursor = renderPlan.showContentCursor,
                                fontSize = fontSize,
                                onContentChange = onContentChange,
                                onCopy = messageCopyOverride(onCopy, copySource),
                                onRegenerate = { lastMsg?.let { onRegenerate?.invoke(it.id) } },
                                onDelete = { lastMsg?.let { onDelete?.invoke(it.id) } },
                                onBranch = { lastMsg?.let { onBranch?.invoke(it.id) } },
                            )
                        }
                    }

                }
            }

            // ── 生成中闪烁光标：仅在 pipeline 无 Content 步骤时（TTFT 期）渲染 ──
            //    正文可见后不再追加独立块光标，避免尾部出现悬空色块。
            if (renderPlan.showStandaloneCursor) {
                StreamingCursor()
            }
        }
    }

    // ── 元信息行（模型名 + 时间戳）──
    group.assistantMessages.lastOrNull()?.let { lastMsg ->
        val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
        val timestamp = remember(lastMsg.createdAt) { timeFormat.format(java.util.Date(lastMsg.createdAt)) }
        val modelDisplayName = if (lastMsg.modelId.isNullOrBlank()) {
            ""
        } else {
            modelDisplayNames[lastMsg.modelId]
                ?.takeIf(String::isNotBlank)
                ?: lastMsg.modelId?.substringAfter("::", lastMsg.modelId).orEmpty()
        }

        AssistantMetadataRow(
            modelDisplayName = modelDisplayName,
            timestamp = timestamp,
            fontSize = fontSize,
        )
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
    ) : PipelineStep()
}

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
        steps.add(PipelineStep.Content(content = ""))
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
                        steps.add(PipelineStep.Content(content = cleanContent))
                    }
                } else {
                    steps.add(PipelineStep.Content(content = msg.content))
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
) {
    var internalExpanded by rememberSaveable { mutableStateOf(false) }
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
    val duration = elapsedMs ?: measuredElapsed
    val seconds = duration?.coerceAtLeast(0)?.div(1000)
    val segments = if (isGenerating) {
        listOf(stringResource(R.string.chat_thinking_elapsed, seconds ?: 0L), stringResource(R.string.chat_status_thinking))
    } else listOf(
        if (seconds == null) stringResource(R.string.chat_status_thought)
        else stringResource(R.string.chat_thought_duration, seconds),
    )
    val expansionState = stringResource(
        if (internalExpanded) R.string.common_state_expanded else R.string.common_state_collapsed,
    )
    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.CHAT_THINKING_TRACE)) {
        CompactStepRow(
            segments = segments,
            icon = Icons.Rounded.CheckCircle,
            active = isGenerating,
            fontSize = fontSize,
            expanded = internalExpanded,
            state = expansionState,
            tag = UiTags.CHAT_THINKING_TOGGLE,
            clickLabel = stringResource(if (internalExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            onClick = { internalExpanded = !internalExpanded },
            preview = if (isGenerating) {{
                val tail = remember(reasoning) { streamingReasoningPreview(reasoning) }
                val scroll = rememberScrollState(initial = Int.MAX_VALUE)
                LaunchedEffect(tail) {
                    snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
                }
                Box(
                    modifier = Modifier.weight(0.52f).fadingReasoningTail(),
                ) {
                    Text(
                        text = tail,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.horizontalScroll(scroll, enabled = false),
                        style = NexaraTypography.labelSmall.copy(fontSize = (fontSize - 1).coerceAtLeast(11).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }} else null,
        )
        if (internalExpanded && reasoning.isNotBlank()) {
            MarkdownText(
                markdown = reasoning,
                isStreaming = isGenerating,
                fontSize = (fontSize - 2).coerceAtLeast(10),
                showCursor = false,
                overrideColor = MaterialTheme.colorScheme.onSurfaceVariant,
                compactSpacing = true,
                modifier = Modifier.fillMaxWidth()
                    .testTag(UiTags.CHAT_THINKING_CONTENT)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantMetadataRow(
    modelDisplayName: String,
    timestamp: String,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val metaStyle = NexaraTypography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontSize = (fontSize - 2).coerceAtLeast(9).sp,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (modelDisplayName.isNotBlank()) {
            Text(
                text = modelDisplayName,
                style = metaStyle,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = timestamp,
            style = metaStyle,
            modifier = Modifier.testTag(UiTags.CHAT_ASSISTANT_TIME_METADATA),
            maxLines = 1,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  InlineToolRow — 紧凑工具执行指示器（气泡内联版本）
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun InlineToolRow(
    steps: List<ExecutionStep>,
    isExecuting: Boolean,
    fontSize: Int,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val invocations = remember(steps) { toolInvocations(steps) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isExecuting) {
        if (isExecuting) while (true) {
            now = System.currentTimeMillis()
            delay(200L)
        }
    }
    val hasError = invocations.any { it.failed }
    val state = stringResource(when {
        isExecuting -> R.string.chat_tool_running
        hasError -> R.string.chat_tool_failed
        invocations.any { !it.complete } -> R.string.chat_tool_pending
        else -> R.string.chat_tool_completed
    })
    val name = if (invocations.size > 1) stringResource(R.string.chat_tools_count, invocations.size)
        else invocations.firstOrNull()?.name ?: stringResource(R.string.chat_tool_default_name)
    val duration = toolGroupDurationMs(invocations, nowMs = if (isExecuting) now else null)
    val segments = buildList {
        add(name)
        add(state)
        if (duration != null) add(toolDurationText(duration).let { stringResource(it.resId, *it.args.toTypedArray()) })
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        CompactStepRow(
            segments = segments,
            icon = if (hasError) Icons.Rounded.Cancel else Icons.Rounded.CheckCircle,
            active = isExecuting,
            fontSize = fontSize,
            expanded = isExpanded,
            state = stringResource(if (isExpanded) R.string.common_state_expanded else R.string.common_state_collapsed),
            clickLabel = stringResource(if (isExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            tag = "CHAT_TOOL_TOGGLE",
            tone = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { isExpanded = !isExpanded },
        )
        if (isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ChatStepSpacing),
            ) {
                invocations.forEach { invocation ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NexaraMetaLine(
                            segments = buildList {
                                add(invocation.name ?: stringResource(R.string.chat_tool_default_name))
                                invocation.durationMs?.let { ms -> add(toolDurationText(ms).let { stringResource(it.resId, *it.args.toTypedArray()) }) }
                            },
                            style = NexaraTypography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        invocation.call?.toolArgs?.takeIf(String::isNotBlank)?.let { args ->
                            Text(
                                text = stringResource(R.string.chat_tool_arguments, args),
                                style = NexaraTypography.bodySmall.copy(fontSize = (fontSize - 1).coerceAtLeast(11).sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        invocation.results.forEach { result ->
                            val images = remember(result.data) { extractToolImageModels(result.data) }
                            images.take(4).forEach { imageModel ->
                                ChatImageThumbnail(
                                    imageModel = imageModel,
                                    contentDescription = stringResource(R.string.chat_cd_tool_image),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            result.content?.takeIf(String::isNotBlank)?.let { content ->
                                Text(
                                    text = content,
                                    style = NexaraTypography.bodySmall.copy(fontSize = (fontSize - 1).coerceAtLeast(11).sp),
                                    color = if (result.type == "error") MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentSegment(
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
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
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
//  PipelineConnector — 竖线连接器
// ─────────────────────────────────────────────────────────────────
// PipelineConnector 已移除（用户裁决：竖向引导线多余，步骤间以间距代替）
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
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val timestamp = remember(message.createdAt) { timeFormat.format(java.util.Date(message.createdAt)) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box {
            Surface(
                shape = NexaraCustomShapes.ChatBubbleUser,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            pressOffset = DpOffset.Zero
                            showMenu = true
                        }
                    )
            ) {
                Column {
                    if (!message.userDocuments.isNullOrEmpty()) {
                        MessageDocumentSummary(message.userDocuments.orEmpty())
                    }
                    if (!message.userImages.isNullOrEmpty()) {
                        Column(
                            modifier = Modifier.padding(
                                start = NexaraSpacing.Small,
                                end = NexaraSpacing.Small,
                                top = NexaraSpacing.Small
                            ),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)
                        ) {
                            message.userImages!!.forEach { dataUrl ->
                                ChatImageThumbnail(
                                    imageModel = dataUrl,
                                    contentDescription = stringResource(R.string.chat_cd_attached_image),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp, max = 240.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                    val legacyAttachments by produceState(
                        initialValue = emptyList(),
                        key1 = message.legacyAttachmentsPayload,
                        key2 = message.userImages,
                    ) {
                        value = withContext(Dispatchers.Default) {
                            LegacyAttachmentCodec.parse(message.legacyAttachmentsPayload).filterNot { item ->
                                item.type == "IMAGE" && item.uri in message.userImages.orEmpty()
                            }
                        }
                    }
                    if (legacyAttachments.isNotEmpty()) {
                        LegacyAttachmentSummary(legacyAttachments)
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = NexaraTypography.bodyMedium.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.5).sp
                            ).forReading(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(NexaraSpacing.Large)
                        )
                    }
                }
            }

            MessageContextMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onCopy = {
                    onCopy?.invoke() ?: copyToClipboard(context, message.content)
                    showMenu = false
                },
                onRegenerate = onRegenerate,
                onBranch = onBranch,
                onDelete = {
                    onDelete?.invoke()
                    showMenu = false
                },
                isUser = true,
                offset = pressOffset
            )
        }
        Text(
            text = timestamp,
            style = NexaraTypography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = (fontSize - 2).coerceAtLeast(9).sp
            ),
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        )
    }
}

@Composable
private fun LegacyAttachmentSummary(items: List<LegacyAttachmentItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small)
            .testTag(UiTags.CHAT_LEGACY_ATTACHMENTS),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
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
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
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
        modifier = Modifier
            .padding(
                start = NexaraSpacing.Large,
                end = NexaraSpacing.Large,
                top = NexaraSpacing.Medium,
            )
            .testTag(UiTags.CHAT_MESSAGE_DOCUMENTS),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
    ) {
        documents.forEach { document ->
            Row(
                modifier = Modifier.heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.widthIn(max = 240.dp)) {
                    Text(
                        text = document.name,
                        style = NexaraTypography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatMessageDocumentBytes(document.sizeBytes),
                        style = NexaraTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
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
    offset: DpOffset = DpOffset.Zero
) {
    NexaraDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
    ) {
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
