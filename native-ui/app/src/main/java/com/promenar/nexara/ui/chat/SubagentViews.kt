package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.subagent.SubagentApproval
import com.promenar.nexara.data.subagent.SubagentApprovalBroker
import com.promenar.nexara.data.subagent.SubagentBatch
import com.promenar.nexara.data.subagent.SubagentEvent
import com.promenar.nexara.data.subagent.SubagentPreset
import com.promenar.nexara.data.subagent.SubagentRun
import com.promenar.nexara.data.subagent.SubagentStatus
import com.promenar.nexara.data.subagent.SubagentToolProfile
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** 当前会话的子代理记录与查看入口；预览与测试中为空，此时不显示分组卡。 */
class SubagentRunsAccess(
    val observe: (stepId: String) -> Flow<SubagentBatch?>,
    val snapshot: (stepId: String) -> SubagentBatch?,
    val onOpen: (stepId: String, index: Int) -> Unit,
)

val LocalSubagentRuns = staticCompositionLocalOf<SubagentRunsAccess?> { null }

@Composable
internal fun subagentName(preset: String?, index: Int): String = when (SubagentPreset.parse(preset)) {
    SubagentPreset.EXPLORER -> stringResource(R.string.subagent_preset_explorer)
    null -> stringResource(R.string.subagent_default_name, index + 1)
}

/** `agent_task` 调用下的子代理分组卡；按该调用的执行步骤 id 读取。 */
@Composable
internal fun SubagentGroupCard(stepId: String, modifier: Modifier = Modifier) {
    val access = LocalSubagentRuns.current ?: return
    val initial = remember(access, stepId) { access.snapshot(stepId) }
    val batch by remember(access, stepId) { access.observe(stepId) }.collectAsState(initial = initial)
    batch?.let { SubagentGroupCardContent(it, onOpen = { index -> access.onOpen(stepId, index) }, modifier = modifier) }
}

@Composable
internal fun SubagentGroupCardContent(
    batch: SubagentBatch,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    now: Long? = null,
) {
    val active = batch.runs.any { it.status.isActive }
    var clock by remember { mutableLongStateOf(now ?: System.currentTimeMillis()) }
    if (now == null && active) {
        LaunchedEffect(batch.stepId) {
            while (true) {
                clock = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag("CHAT_SUBAGENT_GROUP"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (batch.runs.size == 1) stringResource(R.string.subagent_group_single)
                else pluralStringResource(R.plurals.subagent_group_parallel, batch.runs.size, batch.runs.size),
                style = NexaraTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        batch.runs.forEach { run ->
            key(run.index) { SubagentRunRow(run, now ?: clock, onClick = { onOpen(run.index) }) }
        }
    }
}

@Composable
private fun SubagentRunRow(run: SubagentRun, now: Long, onClick: () -> Unit) {
    val name = subagentName(run.preset, run.index)
    val openLabel = stringResource(R.string.subagent_open_cd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .testTag("CHAT_SUBAGENT_ROW"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubagentStatusIcon(run.status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = NexaraTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = run.description,
                style = NexaraTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = subagentStatusText(run, now),
            style = NexaraTypography.labelSmall,
            color = if (run.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 8.dp),
        )
        if (run.status == SubagentStatus.COMPLETED) {
            run.result?.let(::subagentResultSummary)?.let { summary ->
                Text(
                    text = summary,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 8.dp),
                )
            }
        }
        if (run.status.isActive) {
            run.liveOutputKey?.let { liveKey ->
                Box(Modifier.padding(start = 24.dp, end = 8.dp, top = 4.dp)) { RunningToolLiveOutput(liveKey) }
            }
        }
    }
}

/** 卡片上的一行结论：跳过 Markdown 标题取第一行正文，全文只有标题时退回标题。 */
internal fun subagentResultSummary(result: String): String? {
    val lines = result.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val line = lines.firstOrNull { !it.startsWith("#") } ?: lines.firstOrNull() ?: return null
    return line.trimStart('#', ' ').takeIf(String::isNotEmpty)
}

@Composable
private fun SubagentStatusIcon(status: SubagentStatus) {
    when (status) {
        SubagentStatus.RUNNING, SubagentStatus.WAITING_APPROVAL -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        SubagentStatus.COMPLETED -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        else -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun subagentStatusText(run: SubagentRun, now: Long): String = when (run.status) {
    SubagentStatus.RUNNING -> when {
        run.step == 0 -> stringResource(R.string.subagent_status_starting)
        run.currentTool != null -> stringResource(R.string.subagent_status_tool, toolFriendlyName(run.currentTool), run.step, run.maxSteps)
        else -> stringResource(R.string.subagent_status_thinking, run.step, run.maxSteps)
    } + " · " + subagentElapsed((now - run.startedAt).coerceAtLeast(0))
    SubagentStatus.WAITING_APPROVAL -> stringResource(R.string.subagent_status_waiting, toolFriendlyName(run.currentTool))
    SubagentStatus.COMPLETED -> stringResource(R.string.subagent_status_done, subagentElapsed(((run.endedAt ?: now) - run.startedAt).coerceAtLeast(0)))
    SubagentStatus.FAILED -> stringResource(R.string.subagent_status_failed, run.error.orEmpty())
    SubagentStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
    SubagentStatus.INTERRUPTED -> stringResource(R.string.subagent_status_interrupted)
}

@Composable
private fun subagentElapsed(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (seconds < 60) stringResource(R.string.subagent_elapsed_seconds, seconds)
    else stringResource(R.string.subagent_elapsed_minutes, seconds / 60, seconds % 60)
}

/** 子代理运行中的审批卡：标注来源子代理，允许、允许并记住或拒绝该次调用。 */
@Composable
fun SubagentApprovalDialog(broker: SubagentApprovalBroker, sessionTitle: (String) -> String? = { null }) {
    val requests by broker.pending.collectAsState()
    val request = requests.firstOrNull() ?: return
    var remember by remember(request.id) { mutableStateOf(false) }
    val title = sessionTitle(request.sessionId)?.takeIf(String::isNotBlank)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.subagent_approval_title, subagentName(request.subagentPreset, request.subagentIndex))) },
        text = { SubagentApprovalContent(request, title, remember, onRememberChange = { remember = it }) },
        confirmButton = {
            TextButton(modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("SUBAGENT_APPROVAL_ALLOW"), onClick = { broker.resolve(request.id, true, remember) }) {
                Text(stringResource(R.string.subagent_approval_allow))
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag("SUBAGENT_APPROVAL_DENY"), onClick = { broker.resolve(request.id, false) }) {
                Text(stringResource(R.string.subagent_approval_deny))
            }
        },
    )
}

/** 审批卡正文：子代理、任务、工具与目标；无法确定目标时不提供“记住”。 */
@Composable
internal fun SubagentApprovalContent(
    request: SubagentApproval,
    sessionTitle: String?,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("SUBAGENT_APPROVAL"),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        BettboxListGroup {
            Column(modifier = Modifier.padding(NexaraSpacing.Large), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                sessionTitle?.let { Text(stringResource(R.string.script_approval_session, it), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.subagent_approval_task, request.subagentDescription), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.subagent_approval_tool, toolFriendlyName(request.toolName)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                NexaraIdentifierText(request.toolName, style = MaterialTheme.typography.bodySmall)
                request.target?.let { NexaraIdentifierText(ToolExecutionTargets.display(it)) }
            }
        }
        if (request.preview.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                SelectionContainer {
                    Text(
                        request.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(NexaraSpacing.Medium),
                    )
                }
            }
        }
        Text(stringResource(R.string.subagent_approval_timeout_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (request.canRemember) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .toggleable(value = remember, role = Role.Checkbox, onValueChange = onRememberChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = remember, onCheckedChange = null)
                Text(stringResource(R.string.subagent_approval_remember), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 子代理只读查看页：任务、过程时间线与交回结果；没有输入入口。 */
@Composable
fun SubagentViewerScreen(
    batch: SubagentBatch?,
    index: Int,
    modelName: (String) -> String,
    onBack: () -> Unit,
    loading: Boolean = false,
) {
    val run = batch?.runs?.getOrNull(index)
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (run?.status?.isActive == true) {
        LaunchedEffect(Unit) {
            while (true) {
                clock = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    NexaraPageLayout(
        title = run?.let { subagentName(it.preset, it.index) } ?: stringResource(R.string.subagent_group_single),
        onBack = onBack,
        scrollable = false,
        imePadding = false,
    ) {
        if (run == null) {
            if (loading) return@NexaraPageLayout
            Text(stringResource(R.string.subagent_viewer_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@NexaraPageLayout
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SubagentViewerContent(run, modelName(run.modelId), now = clock)
        }
        SubagentViewerFooter()
    }
}

@Composable
internal fun SubagentViewerContent(run: SubagentRun, modelName: String, now: Long = System.currentTimeMillis()) {
    Text(
        text = subagentStatusText(run, now),
        style = MaterialTheme.typography.bodySmall,
        color = if (run.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = NexaraSpacing.RowHorizontal).padding(bottom = NexaraSpacing.Medium),
    )
    NexaraSettingsSection(title = stringResource(R.string.subagent_viewer_task_section)) {
        Column(Modifier.padding(NexaraSpacing.Large), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
            Text(run.description, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            ExpandableToolText(text = run.prompt, collapsedLines = 8)
        }
        SubagentFact(stringResource(R.string.subagent_viewer_profile), stringResource(
            if (run.toolProfile == SubagentToolProfile.READ_ONLY) R.string.subagent_profile_read_only else R.string.subagent_profile_inherit,
        ))
        SubagentFact(stringResource(R.string.subagent_viewer_model), modelName)
        SubagentFact(stringResource(R.string.subagent_viewer_step_limit), run.maxSteps.toString())
        if (run.totalTokens > 0) {
            SubagentFact(stringResource(R.string.subagent_viewer_usage), stringResource(R.string.subagent_tokens, run.totalTokens) + if (run.tokensEstimated) stringResource(R.string.p0_estimated) else "")
        }
    }
    NexaraSettingsSection(title = stringResource(R.string.subagent_viewer_timeline)) {
        Column(Modifier.padding(NexaraSpacing.Large).testTag("SUBAGENT_TIMELINE"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 终止原因已在结果区展示，时间线不再重复最后一条同文错误。
            val events = run.events.let { all ->
                val last = all.lastOrNull()
                if (run.error != null && last?.kind == SubagentEvent.Kind.ERROR && last.text == run.error) all.dropLast(1) else all
            }
            if (events.isEmpty()) Text(stringResource(R.string.subagent_viewer_timeline_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            events.forEach { event -> SubagentEventItem(event) }
            run.liveOutputKey?.takeIf { run.status.isActive }?.let { RunningToolLiveOutput(it) }
        }
    }
    NexaraSettingsSection(title = stringResource(R.string.subagent_viewer_result)) {
        Box(Modifier.padding(NexaraSpacing.Large).testTag("SUBAGENT_RESULT")) {
            when {
                run.result != null -> SelectionContainer { MarkdownText(markdown = run.result, isStreaming = false, compactSpacing = true, modifier = Modifier.fillMaxWidth()) }
                run.error != null -> ToolErrorBlock(run.error)
                else -> Text(stringResource(R.string.subagent_viewer_no_result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubagentFact(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget).padding(horizontal = NexaraSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SubagentEventItem(event: SubagentEvent) {
    when (event.kind) {
        SubagentEvent.Kind.TEXT -> Text(event.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        SubagentEvent.Kind.TOOL_CALL -> SubagentEventBlock(Icons.Rounded.Build, stringResource(R.string.subagent_event_tool_call, toolFriendlyName(event.toolName)), event.toolName) {
            if (event.text.isNotBlank() && event.text != "{}") ExpandableToolText(text = event.text, collapsedLines = 4, arguments = true)
        }
        SubagentEvent.Kind.TOOL_RESULT -> SubagentEventBlock(
            if (event.failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            stringResource(if (event.failed) R.string.subagent_event_tool_failed else R.string.subagent_event_tool_result, toolFriendlyName(event.toolName)),
            null,
        ) {
            if (event.failed) ToolErrorBlock(event.text) else ExpandableToolText(text = event.text, collapsedLines = 6)
        }
        SubagentEvent.Kind.APPROVAL -> SubagentEventBlock(Icons.Rounded.Shield, stringResource(R.string.subagent_event_approval, toolFriendlyName(event.toolName)), null) {
            if (event.text.isNotBlank()) NexaraIdentifierText(event.text, style = MaterialTheme.typography.bodySmall)
        }
        SubagentEvent.Kind.ERROR -> ToolErrorBlock(event.text)
    }
}

@Composable
private fun SubagentEventBlock(icon: ImageVector, title: String, identifier: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = NexaraTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            identifier?.let {
                Text(it, style = NexaraTypography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        content()
    }
}

@Composable
internal fun SubagentViewerFooter() {
    val footer = stringResource(R.string.subagent_viewer_footer)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Medium).semantics { contentDescription = footer }.testTag("SUBAGENT_VIEWER_FOOTER"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).padding(horizontal = NexaraSpacing.Large), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
