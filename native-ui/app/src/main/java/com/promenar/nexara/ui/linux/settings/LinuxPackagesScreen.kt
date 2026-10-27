package com.promenar.nexara.ui.linux.settings

import com.promenar.nexara.ui.theme.NexaraMonospace
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSegment
import com.promenar.nexara.ui.common.NexaraSegmentedControl
import com.promenar.nexara.ui.common.NexaraPageSearch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxPackage
import com.promenar.nexara.domain.linux.LinuxPackageAction
import com.promenar.nexara.domain.linux.LinuxPackageOperation
import com.promenar.nexara.domain.linux.LinuxPackageOrigin
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

internal data class LinuxPackagesActions(
    val onFilter: (LinuxPackageFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onInstall: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onUpgrade: (String) -> Unit = {},
    val onUpgradeAll: () -> Unit = {},
)

@Composable
fun LinuxPackagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: LinuxPackagesViewModel = viewModel(factory = LinuxPackagesViewModel.factory(context.applicationContext as Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    LaunchedEffect(state.failure) {
        val failure = state.failure ?: return@LaunchedEffect
        val label = resources.getString(linuxPackageActionLabel(failure.action))
        val detail = failure.result?.failure?.let { resources.getString(linuxFailureText(it.code)) }
        viewModel.consumeFailure()
        snackbar.showSnackbar(listOfNotNull(resources.getString(R.string.linux_packages_operation_failed, label), detail).joinToString(" "))
    }
    NexaraSettingsPageLayout(
        title = stringResource(R.string.linux_packages_title),
        onBack = onBack,
        horizontalContentPadding = 0.dp,
        search = NexaraPageSearch(
            query = state.query,
            active = searchActive,
            onQueryChange = viewModel::setQuery,
            onActiveChange = { searchActive = it },
            placeholder = stringResource(R.string.linux_packages_search_hint),
            fieldTag = UiTags.LINUX_PACKAGES_SEARCH,
        ),
        actions = {
            IconButton(onClick = viewModel::refreshIndex, enabled = state.operation == null) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.linux_packages_refresh))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LinuxPackagesContent(
                state = state,
                actions = LinuxPackagesActions(
                    onFilter = viewModel::setFilter,
                    onQuery = viewModel::setQuery,
                    onInstall = viewModel::install,
                    onRemove = viewModel::remove,
                    onUpgrade = viewModel::upgrade,
                    onUpgradeAll = viewModel::upgradeAll,
                ),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * 进度条、状态行与过滤芯片固定在列表上方，高度不随操作与可更新数变化，刷新与安装时列表不跳动；
 * 操作输出收进“查看输出”面板。
 */
@Composable
internal fun LinuxPackagesContent(state: LinuxPackagesUiState, actions: LinuxPackagesActions, modifier: Modifier = Modifier) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var lastOperation by remember { mutableStateOf(state.operation) }
    LaunchedEffect(state.operation) { state.operation?.let { lastOperation = it } }
    val searchOnly = searchOnlyResults(state.searchResults, state.installed)
    val installed = state.visibleInstalled
    Column(modifier.fillMaxSize()) {
        LinuxPackagesProgress(state.operation)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = NexaraSpacing.Large, end = NexaraSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.operation?.let { linuxPackageOperationHead(it) }
                    ?: if (state.installed == null) stringResource(R.string.linux_packages_summary_loading)
                    else stringResource(R.string.linux_packages_summary, state.installedCount, state.updateCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (lastOperation != null) {
                TextButton(onClick = { showOutput = true }) { Text(stringResource(R.string.linux_packages_view_output)) }
            }
            FilledTonalButton(
                onClick = actions.onUpgradeAll,
                enabled = state.updateCount > 0 && state.operation == null,
                modifier = Modifier.testTag(UiTags.LINUX_PACKAGES_UPGRADE_ALL),
            ) { Text(stringResource(R.string.linux_packages_upgrade_all)) }
        }
        NexaraSegmentedControl(
            segments = LinuxPackageFilter.entries.map { NexaraSegment(it, filterLabel(it, state.updateCount)) },
            selected = state.filter,
            onSelect = actions.onFilter,
            contentPadding = PaddingValues(horizontal = NexaraSpacing.Large),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = NexaraSpacing.Large),
        ) {
            if (searchOnly != null || state.searching) {
                item(key = "search-header") {
                    SettingsSectionHeader(stringResource(R.string.linux_packages_search_results, state.query.trim()), modifier = Modifier.padding(start = NexaraSpacing.Large, end = NexaraSpacing.Large, top = NexaraSpacing.Large, bottom = NexaraSpacing.XSmall))
                }
                when {
                    state.searching -> item(key = "searching") {
                        Box(Modifier.fillMaxWidth().padding(NexaraSpacing.Large), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    searchOnly.isNullOrEmpty() && installed.isEmpty() -> item(key = "search-empty") {
                        LinuxPackagesEmpty(stringResource(R.string.linux_packages_search_empty, state.query.trim()))
                    }
                    else -> items(searchOnly.orEmpty(), key = { "s-${it.name}" }) { pkg ->
                        LinuxPackageRow(pkg, state.operation, actions, onRequestRemove = { removing = it })
                    }
                }
            }
            if (state.installed != null) {
                item(key = "installed-header") {
                    SettingsSectionHeader(stringResource(R.string.linux_packages_installed_section), modifier = Modifier.padding(start = NexaraSpacing.Large, end = NexaraSpacing.Large, top = NexaraSpacing.Large, bottom = NexaraSpacing.XSmall))
                }
                if (installed.isEmpty()) {
                    item(key = "installed-empty") { LinuxPackagesEmpty(stringResource(R.string.linux_packages_empty)) }
                }
                items(installed, key = { "i-${it.name}" }) { pkg ->
                    LinuxPackageRow(pkg, state.operation, actions, onRequestRemove = { removing = it })
                }
            }
        }
    }
    NexaraBottomSheet(
        show = showOutput && lastOperation != null,
        onDismiss = { showOutput = false },
        title = stringResource(R.string.linux_packages_output_title),
        scrollContent = true,
    ) {
        lastOperation?.let { operation ->
            Text(
                linuxPackageOperationHead(operation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = NexaraSpacing.Small),
            )
            LinuxOutputBox(operation.outputTail.trimEnd().ifEmpty { "—" })
        }
    }
    removing?.let { name ->
        NexaraConfirmDialog(
            title = stringResource(R.string.linux_packages_remove_confirm_title, name),
            message = stringResource(R.string.linux_packages_remove_confirm_body),
            confirmText = stringResource(R.string.linux_packages_remove),
            onConfirm = { removing = null; actions.onRemove(name) },
            onCancel = { removing = null },
            isDestructive = true,
        )
    }
}

/** 顶栏下方 2dp 的进度条；空闲时保留同样高度，避免内容上下移动。 */
@Composable
private fun LinuxPackagesProgress(operation: LinuxPackageOperation?) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (operation == null) return@Box
        val step = operation.step
        val total = operation.totalSteps
        if (step != null && total != null && total > 0) {
            LinearProgressIndicator(progress = { step.toFloat() / total }, modifier = Modifier.fillMaxSize(), drawStopIndicator = {})
        } else {
            LinearProgressIndicator(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LinuxPackagesEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Large),
    )
}

@Composable
private fun LinuxPackageRow(
    pkg: LinuxPackage,
    operation: LinuxPackageOperation?,
    actions: LinuxPackagesActions,
    onRequestRemove: (String) -> Unit,
) {
    val busyHere = operation != null && (pkg.name in operation.names || (operation.action == LinuxPackageAction.UPGRADE && operation.names.isEmpty() && pkg.availableVersion != null))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NexaraSpacing.Large, vertical = 10.dp).testTag(UiTags.linuxPackageRow(pkg.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Column(Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = NexaraMonospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pkg.availableVersion?.let { "${pkg.version} → $it" } ?: pkg.version,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            packageSubtitle(pkg)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val enabled = operation == null
        // 操作位固定最小宽度，按钮文字切换时行内文字不跟着挤动。
        Box(Modifier.widthIn(min = 96.dp), contentAlignment = Alignment.CenterEnd) { when {
            busyHere -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(packageBusyLabel(operation!!.action)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !pkg.installed -> FilledTonalButton(onClick = { actions.onInstall(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_install))
            }
            pkg.availableVersion != null -> FilledTonalButton(onClick = { actions.onUpgrade(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_update))
            }
            pkg.explicit -> OutlinedButton(onClick = { onRequestRemove(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_remove))
            }
        } }
    }
}

/** 第三行：来源、描述与大小；没有可说明的内容时为 null。 */
@Composable
private fun packageSubtitle(pkg: LinuxPackage): String? {
    val origin = when (val origin = pkg.origin) {
        is LinuxPackageOrigin.Agent -> origin.sessionTitle?.takeIf(String::isNotBlank)
            ?.let { stringResource(R.string.linux_packages_origin_agent, it) }
            ?: stringResource(R.string.linux_packages_origin_agent_unnamed)
        LinuxPackageOrigin.Preset -> stringResource(R.string.linux_packages_origin_preset)
        LinuxPackageOrigin.User -> stringResource(R.string.linux_packages_origin_user)
        null -> if (pkg.installed && !pkg.explicit) stringResource(R.string.linux_packages_dependency) else null
    }
    val size = pkg.installedSizeBytes?.let { linuxBytes(it) }
    return listOfNotNull(origin, pkg.description?.takeIf(String::isNotBlank), size).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun filterLabel(filter: LinuxPackageFilter, updates: Int): String = when (filter) {
    LinuxPackageFilter.ALL -> stringResource(R.string.linux_packages_filter_all)
    LinuxPackageFilter.INSTALLED -> stringResource(R.string.linux_packages_filter_installed)
    LinuxPackageFilter.UPDATES -> stringResource(R.string.linux_packages_filter_updates, updates)
    LinuxPackageFilter.AGENT -> stringResource(R.string.linux_packages_filter_agent)
}

/** 软件包操作的一行摘要：动作、包名与 apk 进度。 */
@Composable
internal fun linuxPackageOperationHead(operation: LinuxPackageOperation): String = listOfNotNull(
    stringResource(packageBusyLabel(operation.action)),
    operation.names.joinToString(" ").ifEmpty { null },
    if (operation.step != null && operation.totalSteps != null) stringResource(R.string.linux_packages_progress, operation.step, operation.totalSteps) else null,
).joinToString(" ")

/** 软件包操作的进度摘要与输出尾部；管理页补装附加包时同样使用。 */
@Composable
internal fun linuxPackageOperationText(operation: LinuxPackageOperation): String {
    val head = linuxPackageOperationHead(operation)
    val tail = operation.outputTail.trimEnd().lines().takeLast(4).joinToString("\n")
    return if (tail.isBlank()) head else "$head\n$tail"
}

internal fun linuxPackageActionLabel(action: LinuxPackageAction): Int = when (action) {
    LinuxPackageAction.REFRESH -> R.string.linux_packages_refresh
    LinuxPackageAction.INSTALL -> R.string.linux_packages_install
    LinuxPackageAction.REMOVE -> R.string.linux_packages_remove
    LinuxPackageAction.UPGRADE -> R.string.linux_packages_update
}

private fun packageBusyLabel(action: LinuxPackageAction): Int = when (action) {
    LinuxPackageAction.REFRESH -> R.string.linux_packages_refreshing
    LinuxPackageAction.INSTALL -> R.string.linux_packages_installing
    LinuxPackageAction.REMOVE -> R.string.linux_packages_removing
    LinuxPackageAction.UPGRADE -> R.string.linux_packages_updating
}
