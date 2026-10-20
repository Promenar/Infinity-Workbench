package com.promenar.nexara.ui.settings

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.repository.ToolAllowlistEntry
import com.promenar.nexara.data.repository.ToolAllowlistStore
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.theme.NexaraSpacing
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 白名单条目的作用域，界面据此显示“全局”或所属工作区。 */
internal sealed interface AllowlistScopeLabel {
    data object Global : AllowlistScopeLabel
    data class Workspace(val name: String?) : AllowlistScopeLabel
    data object Session : AllowlistScopeLabel
    data object Mount : AllowlistScopeLabel
}

internal data class ToolAllowlistRow(val entry: ToolAllowlistEntry, val scope: AllowlistScopeLabel)

/** 按 [ToolAllowlistEntry.scopeKey] 的前缀归类；工作区名称取自工作区登记，已删除的工作区名称为 null。 */
internal fun allowlistRows(entries: List<ToolAllowlistEntry>, workspaces: List<WorkspaceEntity>): List<ToolAllowlistRow> {
    val names = workspaces.associate { it.id to it.displayName }
    return entries.map { entry ->
        val key = entry.scopeKey
        val scope = when {
            key == null -> AllowlistScopeLabel.Global
            key.startsWith("workspace:") -> AllowlistScopeLabel.Workspace(names[key.removePrefix("workspace:")])
            key.startsWith("session:") -> AllowlistScopeLabel.Session
            else -> AllowlistScopeLabel.Mount
        }
        ToolAllowlistRow(entry, scope)
    }
}

class ToolAllowlistViewModel(
    private val store: ToolAllowlistStore,
    workspaces: Flow<List<WorkspaceEntity>>,
) : ViewModel() {
    internal val rows: StateFlow<List<ToolAllowlistRow>> = combine(store.entries, workspaces, ::allowlistRows)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: String) {
        viewModelScope.launch { store.remove(id) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as NexaraApplication
                return ToolAllowlistViewModel(app.toolAllowlistStore, app.database.workspaceDao().observeAll()) as T
            }
        }
    }
}

@Composable
fun ToolAllowlistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ToolAllowlistViewModel = viewModel(factory = ToolAllowlistViewModel.factory(context.applicationContext as Application))
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    NexaraSettingsPageLayout(title = stringResource(R.string.tool_allowlist_title), onBack = onBack) { padding ->
        ToolAllowlistContent(
            rows = rows,
            onRemove = viewModel::remove,
            onClear = viewModel::clear,
            modifier = Modifier.padding(padding),
        )
    }
}

/** 白名单列表：每条一行，工具名在上，目标、作用域与加入时间在下，行尾撤销；末尾为全部清空。 */
@Composable
internal fun ToolAllowlistContent(
    rows: List<ToolAllowlistRow>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
    ) {
        NexaraSettingsSection(
            title = stringResource(R.string.tool_allowlist_section),
            footer = stringResource(if (rows.isEmpty()) R.string.tool_allowlist_empty else R.string.tool_allowlist_footer),
        ) {
            rows.forEach { row ->
                val target = ToolExecutionTargets.display(row.entry.target)
                val revokeLabel = stringResource(R.string.tool_allowlist_revoke_cd, "${row.entry.toolName}: $target")
                val details = listOf(
                    target.takeIf { it != row.entry.toolName },
                    allowlistScopeText(row.scope),
                    DateUtils.getRelativeTimeSpanString(row.entry.createdAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                ).filterNotNull().joinToString(" · ")
                NexaraSettingsRowLayout(
                    title = row.entry.toolName,
                    subtitle = details,
                    subtitleIsIdentifier = true,
                    modifier = Modifier.testTag("allowlist_row:${row.entry.id}"),
                    trailingContent = {
                        TextButton(
                            onClick = { onRemove(row.entry.id) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.semantics { contentDescription = revokeLabel },
                        ) { Text(stringResource(R.string.tool_allowlist_revoke)) }
                    },
                )
            }
            if (rows.isNotEmpty()) {
                NexaraSettingsItem(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.tool_allowlist_clear),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                    modifier = Modifier.testTag("allowlist_clear"),
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.tool_allowlist_clear_title)) },
            text = { Text(stringResource(R.string.tool_allowlist_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; onClear() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.tool_allowlist_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_btn_cancel)) } },
        )
    }
}

@Composable
private fun allowlistScopeText(scope: AllowlistScopeLabel): String = when (scope) {
    AllowlistScopeLabel.Global -> stringResource(R.string.tool_allowlist_scope_global)
    is AllowlistScopeLabel.Workspace -> scope.name?.let { stringResource(R.string.tool_allowlist_scope_workspace, it) }
        ?: stringResource(R.string.tool_allowlist_scope_workspace_removed)
    AllowlistScopeLabel.Session -> stringResource(R.string.tool_allowlist_scope_session)
    AllowlistScopeLabel.Mount -> stringResource(R.string.tool_allowlist_scope_mount)
}

/** 工具设置页入口行的图标；与执行预算分组中的其它行保持同一形态。 */
internal val ToolAllowlistEntryIcon = Icons.Rounded.VerifiedUser
