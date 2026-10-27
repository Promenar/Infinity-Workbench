package com.promenar.nexara.ui.linux.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SubdirectoryArrowLeft
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileType
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.ui.chat.components.DirectoryPasteBar
import com.promenar.nexara.ui.chat.components.DirectorySelectionAction
import com.promenar.nexara.ui.chat.components.DirectorySelectionBar
import com.promenar.nexara.ui.common.FilePathRoot
import com.promenar.nexara.ui.common.FilePathRow
import com.promenar.nexara.ui.common.FolderCrumb
import com.promenar.nexara.ui.common.FolderStatusMessage
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraPageSearch
import com.promenar.nexara.ui.common.NexaraSearchTopBar
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.hierarchicalTransition
import com.promenar.nexara.ui.workspace.WorkspaceNameDialog
import kotlinx.coroutines.delay
import com.promenar.nexara.ui.common.NexaraMenuItem

private const val OUTCOME_VISIBLE_MS = 4_000L

/**
 * Linux 文件浏览页。
 *
 * @param onBack 在最外层（根目录且无选择、无打开的文件）时返回上一页。
 * @param onOpenTerminal 打开本地终端：`directory` 为容器内工作目录，`command` 为要执行的命令（浏览页总是 null）。
 * @param onOpenEnvironment 环境未就绪时前往“Linux 环境”页面。
 * @param initialPath 首次进入的容器内目录，默认 Linux 主目录 `/root`。
 * @param onOpenFile 打开文件详情；为 null 时在本页内推入 [LinuxFileScreen]，系统返回关闭详情。
 */
@Composable
fun LinuxFilesScreen(
    onBack: () -> Unit,
    onOpenTerminal: (directory: String, command: String?) -> Unit,
    onOpenEnvironment: () -> Unit,
    initialPath: String = LINUX_HOME_PATH,
    onOpenFile: ((path: String) -> Unit)? = null,
) {
    val linux = (LocalContext.current.applicationContext as NexaraApplication).linux
    val viewModel: LinuxFilesViewModel = viewModel(
        key = "linux_files",
        factory = LinuxFilesViewModel.factory(linux.files, linux.runtime, initialPath),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openedFile by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    AnimatedContent(
        targetState = openedFile,
        transitionSpec = { hierarchicalTransition { if (it != null) 1 else 0 } },
        label = "linux_files_page",
    ) { file ->
        if (file != null) {
            BackHandler { openedFile = null; viewModel.refresh() }
            LinuxFileScreen(
                path = file,
                onBack = { openedFile = null; viewModel.refresh() },
                onOpenTerminal = onOpenTerminal,
            )
        } else {
            BackHandler(enabled = state.selecting || (state.ready && linuxParentPath(state.path) != null)) {
                if (state.selecting) viewModel.clearSelection() else viewModel.navigateUp()
            }
            LinuxFilesContent(
                state = state,
                actions = LinuxFilesActions(
                    onBack = onBack,
                    onNavigate = viewModel::navigate,
                    onGoHome = viewModel::goHome,
                    onQueryChange = viewModel::setQuery,
                    onRefresh = viewModel::refresh,
                    onSort = viewModel::setSort,
                    onToggleHidden = viewModel::toggleShowHidden,
                    onCreate = viewModel::requestCreate,
                    onOpen = { entry ->
                        viewModel.open(entry)?.let { path -> if (onOpenFile != null) onOpenFile(path) else openedFile = path }
                    },
                    onToggleSelect = viewModel::toggleSelection,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onCopy = viewModel::copySelection,
                    onPaste = viewModel::paste,
                    onCancelClipboard = viewModel::cancelClipboard,
                    onCompress = viewModel::requestCompress,
                    onOpenTerminal = {
                        val directory = viewModel.terminalDirectory()
                        viewModel.clearSelection()
                        onOpenTerminal(directory, null)
                    },
                    onDelete = viewModel::requestDelete,
                    onRename = viewModel::requestRename,
                    onChmod = { viewModel.requestChmod() },
                    onDismissNotice = viewModel::dismissNotice,
                    onDismissOutcome = viewModel::dismissOutcome,
                    onOpenEnvironment = onOpenEnvironment,
                    onConfirmName = viewModel::confirmName,
                    onConfirmChmod = viewModel::confirmChmod,
                    onConfirmDelete = viewModel::confirmDelete,
                    onDismissDialog = viewModel::dismissDialog,
                    onNameChanged = viewModel::clearDialogError,
                ),
            )
        }
    }
}

/** 浏览页的全部交互回调；预览与截图测试传空实现。 */
internal data class LinuxFilesActions(
    val onBack: () -> Unit = {},
    val onNavigate: (String) -> Unit = {},
    val onGoHome: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSort: (LinuxFileSort) -> Unit = {},
    val onToggleHidden: () -> Unit = {},
    val onCreate: (folder: Boolean) -> Unit = {},
    val onOpen: (LinuxFileEntry) -> Unit = {},
    val onToggleSelect: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onCopy: (cut: Boolean) -> Unit = {},
    val onPaste: () -> Unit = {},
    val onCancelClipboard: () -> Unit = {},
    val onCompress: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onChmod: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onDismissOutcome: () -> Unit = {},
    val onOpenEnvironment: () -> Unit = {},
    val onConfirmName: (String) -> Unit = {},
    val onConfirmChmod: (Int) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onNameChanged: () -> Unit = {},
)

@Composable
internal fun LinuxFilesContent(
    state: LinuxFilesUiState,
    actions: LinuxFilesActions,
    modifier: Modifier = Modifier,
) {
    var searchActive by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            NexaraSearchTopBar(
                title = stringResource(R.string.linux_files_title),
                search = if (state.ready) {
                    NexaraPageSearch(
                        query = state.query,
                        active = searchActive,
                        onQueryChange = actions.onQueryChange,
                        onActiveChange = { searchActive = it },
                        placeholder = stringResource(R.string.linux_files_search_hint),
                        fieldTag = UiTags.LINUX_FILES_SEARCH,
                    )
                } else {
                    null
                },
                onBack = actions.onBack,
                actions = {
                    if (state.ready) {
                        LinuxCreateButton(enabled = !state.selecting && !state.operating && state.error == null, onCreate = actions.onCreate)
                        LinuxMoreMenu(state, actions)
                    }
                },
            )
            if (!state.ready) {
                LinuxNotReady(state.runtime, actions.onOpenEnvironment)
                return@Column
            }
            LinuxPathRow(state, actions)
            LinuxFilesMessages(state, actions)
            LinuxEntriesList(state, actions, Modifier.weight(1f))
            AnimatedVisibility(visible = state.operating, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Large))
            }
            LinuxBottomBar(state, actions)
        }
    }
    LinuxFilesDialogs(state, actions)
}

@Composable
private fun LinuxNotReady(runtime: LinuxRuntimeState?, onOpenEnvironment: () -> Unit) {
    val message = when (runtime) {
        is LinuxRuntimeState.Installing -> R.string.linux_files_not_ready_installing
        is LinuxRuntimeState.Unavailable -> R.string.linux_files_not_ready_unavailable
        else -> R.string.linux_files_not_ready_message
    }
    LinuxCenteredMessage(
        title = stringResource(R.string.linux_files_not_ready_title),
        message = stringResource(message),
        icon = Icons.Rounded.Terminal,
        modifier = Modifier.testTag(UiTags.LINUX_FILES_NOT_READY),
    ) {
        // 尚未加载运行时状态时不显示入口，避免一闪而过。
        if (runtime != null) {
            Button(
                onClick = onOpenEnvironment,
                modifier = Modifier
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .testTag(UiTags.LINUX_FILES_OPEN_ENVIRONMENT),
            ) { Text(stringResource(R.string.linux_files_open_environment)) }
        }
    }
}

/** 路径行：`~` 与 `/` 两个起点片段加相对起点的各级目录；下方一行说明当前范围的重置行为并放排序。 */
@Composable
private fun LinuxPathRow(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    val crumbs = remember(state.path) { linuxCrumbs(state.path) }
    val pathDescription = stringResource(R.string.linux_files_path_cd, state.path)
    FilePathRow(
        roots = listOf(
            FilePathRoot(
                label = "~",
                contentDescription = stringResource(R.string.linux_files_switch_to_home),
                selected = crumbs.underHome,
                onClick = actions.onGoHome,
                tag = UiTags.LINUX_FILES_ROOT_HOME,
            ),
            FilePathRoot(
                label = "/",
                contentDescription = stringResource(R.string.linux_files_switch_to_root),
                selected = !crumbs.underHome,
                onClick = { actions.onNavigate("/") },
                tag = UiTags.LINUX_FILES_ROOT_SYSTEM,
            ),
        ),
        trail = remember(crumbs) { crumbs.trail.map { FolderCrumb(id = it.path, name = it.name) } },
        onNavigateToCrumb = { index -> actions.onNavigate(crumbs.trail[index].path) },
        enabled = !state.selecting,
        crumbStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = NexaraMonospace),
        modifier = Modifier
            .padding(horizontal = NexaraSpacing.Small)
            .semantics { contentDescription = pathDescription }
            .testTag(UiTags.LINUX_FILES_PATH),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = NexaraSpacing.Large, end = NexaraSpacing.XSmall),
    ) {
        Text(
            stringResource(if (state.atHome) R.string.linux_files_subtitle_home else R.string.linux_files_subtitle_root),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LinuxSortButton(state.sort, actions.onSort)
    }
}

@Composable
private fun LinuxSortButton(sort: LinuxFileSort, onSort: (LinuxFileSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = sortLabel(sort)
    val description = stringResource(R.string.linux_files_sort_cd, label)
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = NexaraSpacing.Medium),
            modifier = Modifier
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .semantics { contentDescription = description }
                .testTag(UiTags.LINUX_FILES_SORT),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(NexaraSpacing.XSmall))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LinuxFileSort.entries.forEach { option ->
                NexaraMenuItem(
                    text = { Text(sortLabel(option)) },
                    onClick = {
                        expanded = false
                        onSort(option)
                    },
                    trailingIcon = if (option == sort) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    modifier = Modifier.semantics { selected = option == sort },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: LinuxFileSort): String = stringResource(
    when (sort) {
        LinuxFileSort.NAME -> R.string.linux_files_sort_name
        LinuxFileSort.MODIFIED -> R.string.linux_files_sort_modified
        LinuxFileSort.SIZE -> R.string.linux_files_sort_size
    },
)

@Composable
private fun LinuxCreateButton(enabled: Boolean, onCreate: (folder: Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILES_NEW),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.linux_files_new))
        }
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NexaraMenuItem(
                text = { Text(stringResource(R.string.linux_files_new_file)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreate(false)
                },
            )
            NexaraMenuItem(
                text = { Text(stringResource(R.string.linux_files_new_folder)) },
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreate(true)
                },
            )
        }
    }
}

/** 顶栏更多：显示隐藏文件、在当前目录打开终端。 */
@Composable
private fun LinuxMoreMenu(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    var expanded by remember { mutableStateOf(false) }
    val hiddenState = stringResource(if (state.showHidden) R.string.linux_files_state_on else R.string.linux_files_state_off)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILES_MORE),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.linux_files_more))
        }
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NexaraMenuItem(
                text = { Text(stringResource(R.string.linux_files_show_hidden)) },
                leadingIcon = { Icon(if (state.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null) },
                trailingIcon = { Checkbox(checked = state.showHidden, onCheckedChange = null) },
                onClick = { actions.onToggleHidden() },
                modifier = Modifier
                    .semantics { stateDescription = hiddenState }
                    .testTag(UiTags.LINUX_FILES_HIDDEN_TOGGLE),
            )
            NexaraMenuItem(
                text = { Text(stringResource(R.string.linux_files_open_terminal_here)) },
                leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                enabled = !state.selecting,
                onClick = {
                    expanded = false
                    actions.onOpenTerminal()
                },
            )
        }
    }
}

@Composable
private fun LinuxFilesMessages(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    val notice = state.notice
    AnimatedVisibility(visible = notice != null, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
        // 退出动画期间提示已清空，保留空文本让区域平滑收起。
        val text = when (notice) {
            is LinuxFilesNotice.RuntimeBinding -> stringResource(R.string.linux_files_binding_notice, notice.linkName, notice.target)
            is LinuxFilesNotice.Failure -> linuxErrorText(notice.code)
            null -> ""
        }
        LinuxNoticeBar(
            text = text,
            onDismiss = actions.onDismissNotice,
            error = notice is LinuxFilesNotice.Failure,
            modifier = Modifier
                .padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall)
                .testTag(UiTags.LINUX_FILES_NOTICE),
        )
    }
    state.outcome?.let { outcome ->
        Text(
            linuxOutcomeText(outcome),
            style = MaterialTheme.typography.bodySmall,
            color = if (outcome.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.XSmall)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(UiTags.LINUX_FILES_OUTCOME),
        )
        LaunchedEffect(outcome) {
            delay(OUTCOME_VISIBLE_MS)
            actions.onDismissOutcome()
        }
    }
}

@Composable
private fun LinuxEntriesList(state: LinuxFilesUiState, actions: LinuxFilesActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Small)) {
        AnimatedVisibility(visible = state.loading, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall))
        }
        val error = state.error
        val entries = state.visibleEntries
        // 主目录与根目录本身由路径行的起点片段到达，不再显示“上一级”。
        val showUp = state.path != LINUX_HOME_PATH && linuxParentPath(state.path) != null
        when {
            error != null -> FolderStatusMessage(
                text = stringResource(R.string.linux_files_load_failed, linuxErrorText(error)),
                error = true,
                modifier = Modifier.testTag(UiTags.LINUX_FILES_STATUS),
            ) {
                TextButton(onClick = actions.onRefresh, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.shared_btn_retry))
                }
            }
            entries.isEmpty() && !state.loading -> Column {
                if (showUp) LinuxUpRow(enabled = !state.selecting) { linuxParentPath(state.path)?.let(actions.onNavigate) }
                FolderStatusMessage(
                    text = stringResource(if (state.query.isBlank()) R.string.linux_files_empty else R.string.common_search_no_results),
                    modifier = Modifier.testTag(UiTags.LINUX_FILES_STATUS),
                )
            }
            entries.isEmpty() -> Unit
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .bettboxListGroup()
                    .testTag(UiTags.LINUX_FILES_LIST),
                contentPadding = PaddingValues(vertical = NexaraSpacing.XSmall),
            ) {
                if (showUp) {
                    item(key = "..") {
                        LinuxUpRow(enabled = !state.selecting) { linuxParentPath(state.path)?.let(actions.onNavigate) }
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    LinuxFileRow(
                        entry = entry,
                        nowMs = state.nowMs,
                        selecting = state.selecting,
                        checked = entry.path in state.selection,
                        onClick = { actions.onOpen(entry) },
                        onLongClick = { actions.onToggleSelect(entry.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinuxUpRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = NexaraSpacing.Large, vertical = 6.dp)
            .testTag(UiTags.LINUX_FILES_UP),
    ) {
        Icon(Icons.Rounded.SubdirectoryArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.linux_files_up), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinuxFileRow(
    entry: LinuxFileEntry,
    nowMs: Long,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = linuxEntryIcon(entry)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (checked) Modifier.background(colors.secondaryContainer.copy(alpha = 0.5f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { if (selecting) selected = checked }
            .padding(horizontal = NexaraSpacing.Large, vertical = 6.dp)
            .testTag(UiTags.linuxFileRow(entry.path)),
    ) {
        if (selecting) {
            Icon(
                if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) colors.primary else colors.outline,
                modifier = Modifier.size(22.dp),
            )
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).alpha(if (entry.isHidden) 0.6f else 1f))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.isHidden) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                linuxEntryMeta(entry, nowMs),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selecting && entry.opensAsDirectory) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun linuxEntryIcon(entry: LinuxFileEntry): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val colors = MaterialTheme.colorScheme
    return when {
        entry.type == LinuxFileType.DIRECTORY -> Icons.Rounded.Folder to colors.primary
        entry.type == LinuxFileType.SYMLINK -> Icons.Rounded.Link to colors.tertiary
        entry.isExecutableFile() -> Icons.Rounded.Terminal to colors.onSurfaceVariant
        linuxLooksBinaryByName(entry.name) -> Icons.Rounded.Memory to colors.onSurfaceVariant
        linuxKindByName(entry.name) == LinuxFileKind.ARCHIVE -> Icons.Rounded.FolderZip to colors.onSurfaceVariant
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to colors.onSurfaceVariant
    }
}

/** 条目第二行：链接显示目标；其余为权限串、属主（隐藏项显示“隐藏”）、相对时间，文件再加大小与“二进制”。 */
@Composable
private fun linuxEntryMeta(entry: LinuxFileEntry, nowMs: Long): String {
    if (entry.type == LinuxFileType.SYMLINK) {
        return stringResource(R.string.linux_files_meta_link, entry.symlinkTarget.orEmpty())
    }
    val parts = mutableListOf(
        formatLinuxPermissions(entry.type, entry.mode),
        if (entry.isHidden) stringResource(R.string.linux_files_meta_hidden) else entry.owner,
        linuxRelativeTimeText(linuxRelativeTime(entry.modifiedMs, nowMs)),
    )
    if (entry.type == LinuxFileType.FILE) {
        parts += formatLinuxSize(entry.size)
        if (linuxLooksBinaryByName(entry.name)) parts += stringResource(R.string.linux_files_meta_binary)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun LinuxBottomBar(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    val clipboard = state.clipboard
    AnimatedContent(
        targetState = when {
            state.selecting -> 1
            clipboard != null -> 2
            else -> 0
        },
        transitionSpec = { NexaraMotion.fadeThrough() },
        label = "linux_files_bottom_bar",
    ) { bar ->
        Box(Modifier.padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Small)) {
            when (bar) {
                1 -> LinuxSelectionBar(state, actions)
                2 -> clipboard?.let {
                    DirectoryPasteBar(
                        clipboard = it,
                        canPaste = state.canPaste,
                        busy = state.operating,
                        onPaste = actions.onPaste,
                        onCancel = actions.onCancelClipboard,
                    )
                }
                else -> Spacer(Modifier.fillMaxWidth())
            }
        }
    }
}

/** 选择期间的底部操作栏：共用目录选择栏，补充全选、修改权限、压缩与在终端打开。 */
@Composable
private fun LinuxSelectionBar(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    val busy = state.operating
    val single = state.selectedEntries.singleOrNull()
    DirectorySelectionBar(
        count = state.selection.size,
        busy = busy,
        onCopy = { actions.onCopy(false) },
        onMove = { actions.onCopy(true) },
        onRename = actions.onRename,
        onDelete = actions.onDelete,
        onClear = actions.onClearSelection,
        modifier = Modifier.testTag(UiTags.LINUX_FILES_SELECTION_BAR),
        topActions = {
            IconButton(
                onClick = actions.onSelectAll,
                enabled = state.selection.size < state.visibleEntries.size,
                modifier = Modifier.testTag(UiTags.LINUX_FILES_SELECT_ALL),
            ) {
                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.linux_files_select_all))
            }
            IconButton(
                onClick = actions.onChmod,
                enabled = single != null && single.type != LinuxFileType.SYMLINK && !busy,
                modifier = Modifier.testTag(UiTags.LINUX_FILES_ACTION_CHMOD),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.linux_files_chmod))
            }
        },
        extraActions = {
            DirectorySelectionAction(Icons.Rounded.FolderZip, R.string.linux_files_action_compress, !busy, actions.onCompress, UiTags.LINUX_FILES_ACTION_COMPRESS)
            DirectorySelectionAction(Icons.Rounded.Terminal, R.string.linux_files_action_terminal, true, actions.onOpenTerminal, UiTags.LINUX_FILES_ACTION_TERMINAL)
        },
    )
}

@Composable
private fun LinuxFilesDialogs(state: LinuxFilesUiState, actions: LinuxFilesActions) {
    val dialog = state.dialog ?: return
    val error = state.dialogError?.let { linuxErrorText(it) }
    key(dialog) {
        when (dialog) {
            is LinuxFilesDialog.CreateFile, is LinuxFilesDialog.CreateFolder -> WorkspaceNameDialog(
                title = stringResource(if (dialog is LinuxFilesDialog.CreateFolder) R.string.linux_files_new_folder else R.string.linux_files_new_file),
                label = stringResource(R.string.linux_files_name_label),
                confirmLabel = stringResource(R.string.linux_files_create),
                onConfirm = actions.onConfirmName,
                onDismiss = actions.onDismissDialog,
                error = error,
                busy = state.operating,
                onValueChange = actions.onNameChanged,
            )
            is LinuxFilesDialog.Rename -> WorkspaceNameDialog(
                title = stringResource(R.string.directory_action_rename),
                label = stringResource(R.string.linux_files_name_label),
                confirmLabel = stringResource(R.string.directory_action_rename),
                onConfirm = actions.onConfirmName,
                onDismiss = actions.onDismissDialog,
                initialValue = dialog.entry.name,
                error = error,
                busy = state.operating,
                onValueChange = actions.onNameChanged,
            )
            is LinuxFilesDialog.Compress -> WorkspaceNameDialog(
                title = stringResource(R.string.linux_files_compress_title),
                label = stringResource(R.string.linux_files_name_label),
                confirmLabel = stringResource(R.string.linux_files_action_compress),
                onConfirm = actions.onConfirmName,
                onDismiss = actions.onDismissDialog,
                initialValue = dialog.defaultName,
                hint = stringResource(R.string.linux_files_compress_hint),
                error = error,
                busy = state.operating,
                onValueChange = actions.onNameChanged,
            )
            is LinuxFilesDialog.Chmod -> LinuxChmodDialog(
                entry = dialog.entry,
                error = state.dialogError,
                onConfirm = actions.onConfirmChmod,
                onDismiss = actions.onDismissDialog,
            )
            is LinuxFilesDialog.ConfirmDelete -> NexaraConfirmDialog(
                onDismissRequest = actions.onDismissDialog,
                title = pluralStringResource(R.plurals.directory_delete_title, dialog.paths.size, dialog.paths.size),
                message = stringResource(R.string.linux_files_delete_message),
                confirmText = stringResource(R.string.directory_action_delete),
                onConfirm = actions.onConfirmDelete,
                onCancel = actions.onDismissDialog,
                isDestructive = true,
                confirmButtonModifier = Modifier.testTag(UiTags.LINUX_FILES_DELETE_CONFIRM),
            )
        }
    }
}
