package com.promenar.nexara.ui.startup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.startup.StartupIssue
import com.promenar.nexara.startup.StartupIssueAction
import com.promenar.nexara.startup.StartupIssueResolution
import com.promenar.nexara.startup.StartupScopeKind
import com.promenar.nexara.startup.StartupStep
import com.promenar.nexara.startup.diagnostics.AppDiagnostics
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSheetHeight
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

object StartupIssuesTags {
    const val LINUX_BANNER = "startup_linux_banner"
    const val BANNER = "startup_issues_banner"
    const val SESSION_BANNER = "startup_issues_session_banner"
    const val SHEET = "startup_issues_sheet"
    const val DIAGNOSE = "startup_issues_diagnose"
    const val EXPORT = "startup_issues_export"
    const val RECORDS = "startup_issues_records"
    fun issue(id: String) = "startup_issue_$id"
    fun action(id: String, action: StartupIssueAction) = "startup_issue_${id}_${action.name.lowercase()}"
}

/** 面板里一条问题的显示内容；范围名称只在本机界面使用。 */
data class StartupIssueRow(val issue: StartupIssue, val scopeName: String)

/** 当前会话或其工作区受启动问题影响时只读。 */
fun List<StartupIssue>.affectsSession(sessionId: String?, workspaceRootUuid: String?): Boolean = any { issue ->
    when (issue.scope.kind) {
        StartupScopeKind.SESSION -> sessionId != null && issue.scope.id == sessionId
        StartupScopeKind.WORKSPACE_ROOT -> workspaceRootUuid != null && issue.scope.id == workspaceRootUuid
        else -> false
    }
}

/** 问题标题按启动步骤给出，摘要是脱敏后的技术原因。 */
internal fun StartupStep.titleRes(): Int = when (this) {
    StartupStep.DUAL_DATABASE, StartupStep.BACKUP_RESTORE, StartupStep.WRITER_INITIALIZATION -> R.string.startup_issue_title_data
    StartupStep.WORKSPACE_INVENTORY -> R.string.startup_issue_title_inventory
    StartupStep.LEGACY_ADOPTION -> R.string.startup_issue_title_adoption
    StartupStep.WORKSPACE_JOURNAL -> R.string.startup_issue_title_file_operation
    StartupStep.SESSION_JOURNAL -> R.string.startup_issue_title_session_delete
    StartupStep.TOMBSTONES -> R.string.startup_issue_title_recycle
    StartupStep.TOOL_LEDGER -> R.string.startup_issue_title_tool_ledger
    StartupStep.VECTOR_QUEUE -> R.string.startup_issue_title_vectors
    StartupStep.BACKGROUND_JOBS -> R.string.startup_issue_title_background
    StartupStep.HEADLESS_INTERACTIONS -> R.string.startup_issue_title_confirmations
    StartupStep.SCHEDULED_TASKS -> R.string.startup_issue_title_schedules
    StartupStep.MODEL_CATALOG -> R.string.startup_issue_title_catalog
    StartupStep.MEMORY_CLEANUP -> R.string.startup_issue_title_memory
}

/** 会话列表顶部：有启动问题时照常进入应用，只提示受影响的范围。 */
@Composable
fun StartupIssuesBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    WarningStrip(
        text = pluralStringResource(R.plurals.startup_issues_banner, count, count),
        action = stringResource(R.string.startup_issues_view),
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.BANNER),
    )
}

/** 会话列表顶部：Linux 环境不可用时提示受影响的 Linux 会话数，不阻止进入应用。 */
@Composable
fun LinuxUnavailableBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    WarningStrip(
        text = pluralStringResource(R.plurals.run_mode_startup_banner, count, count),
        action = stringResource(R.string.run_mode_startup_action),
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.LINUX_BANNER),
    )
}

/** 会话顶栏下方：该会话或其工作区暂停写入。 */
@Composable
fun StartupWritePausedBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    WarningStrip(
        text = stringResource(R.string.startup_issues_session_paused),
        action = stringResource(R.string.startup_issues_handle),
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.SESSION_BANNER),
    )
}

@Composable
internal fun WarningStrip(text: String, action: String, icon: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.tertiaryContainer)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onTertiaryContainer) {
            icon()
            Text(text, style = NexaraTypography.bodySmall, color = colors.onTertiaryContainer, modifier = Modifier.weight(1f))
            Text(action, style = NexaraTypography.labelMedium, color = colors.onTertiaryContainer)
        }
    }
}

/**
 * 启动问题面板：逐条列出问题与可用处置，底部是“让 Agent 诊断”、诊断记录与导出。
 * [busyIssueId] 非空时该条处置进行中，其余按钮照常可用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartupIssuesSheetContent(
    rows: List<StartupIssueRow>,
    busyIssueId: String?,
    message: String?,
    canDiagnose: Boolean,
    onAction: (StartupIssue, StartupIssueAction) -> Unit,
    onDiagnose: () -> Unit,
    onViewRecords: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NexaraSpacing.XLarge, vertical = NexaraSpacing.Small)
            .testTag(StartupIssuesTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Text(
            stringResource(R.string.startup_issues_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.startup_issues_intro), style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant)
        if (rows.isEmpty()) {
            Text(stringResource(R.string.startup_issues_none), style = NexaraTypography.bodyMedium, color = colors.onSurfaceVariant)
        }
        rows.forEach { row ->
            val issue = row.issue
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(NexaraSpacing.Medium)
                    .testTag(StartupIssuesTags.issue(issue.id)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(issue.step.titleRes()), style = NexaraTypography.bodyMedium, color = colors.onSurface)
                Text(row.scopeName, style = NexaraTypography.labelMedium, color = colors.onSurfaceVariant)
                Text(issue.summary, style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    modifier = Modifier.padding(top = NexaraSpacing.Small),
                ) {
                    if (busyIssueId == issue.id) {
                        CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                    } else {
                        if (StartupIssueAction.RETRY in issue.actions) {
                            OutlinedButton(
                                onClick = { onAction(issue, StartupIssueAction.RETRY) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag(StartupIssuesTags.action(issue.id, StartupIssueAction.RETRY)),
                            ) { Text(stringResource(R.string.startup_issues_retry)) }
                        }
                        if (StartupIssueAction.ABANDON in issue.actions) {
                            TextButton(
                                onClick = { onAction(issue, StartupIssueAction.ABANDON) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag(StartupIssuesTags.action(issue.id, StartupIssueAction.ABANDON)),
                            ) { Text(stringResource(R.string.startup_issues_abandon)) }
                        }
                    }
                }
            }
        }
        message?.let { Text(it, style = NexaraTypography.bodySmall, color = colors.primary) }
        if (rows.isNotEmpty()) {
            Button(
                onClick = onDiagnose,
                enabled = canDiagnose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(StartupIssuesTags.DIAGNOSE),
            ) { Text(stringResource(R.string.startup_issues_diagnose)) }
            if (!canDiagnose) {
                Text(stringResource(R.string.startup_issues_diagnose_unavailable), style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
            OutlinedButton(onClick = onViewRecords, modifier = Modifier.heightIn(min = 48.dp).testTag(StartupIssuesTags.RECORDS)) {
                Text(stringResource(R.string.startup_issues_records))
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp).testTag(StartupIssuesTags.EXPORT)) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.startup_issues_export), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(stringResource(R.string.startup_issues_abandon_note), style = NexaraTypography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/**
 * 有状态的启动问题面板：处置、放弃确认、诊断会话、诊断记录与导出都在这里完成。
 * [diagnosisAgentId] 给出诊断会话使用的助手；问题属于某个会话时优先用该会话的助手。
 */
@Composable
fun StartupIssuesSheet(
    onDismiss: () -> Unit,
    diagnosisAgentId: (List<StartupIssue>) -> String?,
    onOpenSession: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as NexaraApplication
    val issues by app.startupIssues.collectAsState()
    val scope = rememberCoroutineScope()
    var busyIssueId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmAbandon by remember { mutableStateOf<StartupIssue?>(null) }
    var records by remember { mutableStateOf<String?>(null) }
    val resolvedText = stringResource(R.string.startup_issues_resolved)
    val failedText = stringResource(R.string.startup_issues_failed)
    val exportedText = stringResource(R.string.startup_issues_exported)
    val exportFailedText = stringResource(R.string.startup_issues_export_failed)
    val noRecordsText = stringResource(R.string.startup_issues_no_records)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                checkNotNull(app.contentResolver.openOutputStream(uri)).use { app.exportStartupDiagnostics(it) }
            }.fold({ exportedText }, { exportFailedText })
        }
    }
    fun resolve(issue: StartupIssue, action: StartupIssueAction, confirmed: Boolean) {
        busyIssueId = issue.id
        scope.launch {
            val result = runCatching { app.resolveStartupIssue(issue.id, action, confirmed) }
                .getOrElse { StartupIssueResolution.Failed(failedText) }
            busyIssueId = null
            when (result) {
                StartupIssueResolution.ConfirmationRequired -> confirmAbandon = issue
                StartupIssueResolution.Resolved, StartupIssueResolution.NotFound -> message = resolvedText
                StartupIssueResolution.UnsupportedAction -> message = failedText
                is StartupIssueResolution.Failed -> message = "$failedText ${result.summary}"
            }
        }
    }
    NexaraBottomSheet(show = true, onDismiss = onDismiss, height = NexaraSheetHeight.Content) {
        val agentId = diagnosisAgentId(issues)
        StartupIssuesSheetContent(
            rows = issues.map { StartupIssueRow(it, app.startupIssueManager.scopeDisplayName(it.scope)) },
            busyIssueId = busyIssueId,
            message = message,
            canDiagnose = agentId != null,
            onAction = { issue, action ->
                if (action == StartupIssueAction.ABANDON) confirmAbandon = issue else resolve(issue, action, confirmed = false)
            },
            onDiagnose = {
                val id = agentId ?: return@StartupIssuesSheetContent
                scope.launch {
                    runCatching { app.createDiagnosticSession(issues.map { it.id }, id) }
                        .onSuccess { session -> onDismiss(); onOpenSession(session.id) }
                        .onFailure { message = failedText }
                }
            },
            onViewRecords = { scope.launch { records = AppDiagnostics.recent().ifBlank { noRecordsText } } },
            onExport = { exportLauncher.launch("nexara-diagnostics.txt") },
        )
    }
    confirmAbandon?.let { issue ->
        Dialog(onDismissRequest = { confirmAbandon = null }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.startup_issues_abandon_title),
                message = stringResource(R.string.startup_issues_abandon_message),
                confirmText = stringResource(R.string.startup_issues_abandon),
                onConfirm = { confirmAbandon = null; resolve(issue, StartupIssueAction.ABANDON, confirmed = true) },
                onCancel = { confirmAbandon = null },
                isDestructive = true,
            )
        }
    }
    records?.let { text -> DiagnosticRecordsDialog(text, onDismiss = { records = null }) }
}

/** 脱敏诊断记录全文，可选择复制。 */
@Composable
fun DiagnosticRecordsDialog(text: String, onDismiss: () -> Unit) = com.promenar.nexara.ui.common.MonospaceTextDialog(
    title = stringResource(R.string.startup_issues_records),
    message = stringResource(R.string.startup_issues_records_note),
    text = text,
    onDismiss = onDismiss,
)
