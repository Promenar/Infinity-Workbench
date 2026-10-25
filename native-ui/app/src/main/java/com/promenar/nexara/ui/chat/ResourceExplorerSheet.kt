package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.nexara.ui.theme.NexaraMotion
import androidx.compose.runtime.remember
import com.promenar.nexara.ui.workspace.WorkspacePickerSheet
import com.promenar.nexara.ui.workspace.SessionWorkspaceUi
import com.promenar.nexara.ui.workspace.SessionWorkspaceBar
import com.promenar.nexara.ui.workspace.AgentReachSheet
import com.promenar.nexara.data.workspace.WorkspaceSwitchResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.promenar.nexara.ui.common.NexaraIdentifierText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import com.promenar.nexara.ui.common.LocalNexaraPanePresentation
import com.promenar.nexara.ui.common.NexaraPanePresentation
import com.promenar.nexara.ui.common.NexaraPageLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.nexara.R
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.ui.chat.components.WorkspaceFolderBrowser
import com.promenar.nexara.ui.chat.components.RecycleBinPanel
import com.promenar.nexara.ui.chat.components.ResourceExplorerViewModel
import com.promenar.nexara.ui.chat.components.ResourceExplorerWorkspaceMode
import com.promenar.nexara.ui.chat.components.DirectoryFileActions
import com.promenar.nexara.ui.chat.components.DirectoryRecycleList
import com.promenar.nexara.ui.chat.components.DirectoryWorkspaceBrowser
import com.promenar.nexara.ui.chat.components.ExternalFoldersPanel
import com.promenar.nexara.ui.chat.components.rememberWorkspaceMediaOpener
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraNavigationHeader
import com.promenar.nexara.ui.theme.hierarchicalTransition
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.git.GitEntrySummary
import com.promenar.nexara.ui.git.GitWorkspaceEntry
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.workspace.workspaceKindLabel
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

/**
 * 资源管理器二级页面：由 [com.promenar.nexara.ui.common.NexaraAdaptivePaneHost] 承载，窄窗口整页、宽窗口停靠侧栏。
 * Git 变更作为上一层压在本页之上打开，返回后本页的目录位置保持不变。
 */
@Composable
fun ResourceExplorerPage(
    onBack: () -> Unit,
    onOpenGit: () -> Unit,
    /** Git 页关闭时递增；本页据此刷新 Git 入口的分支与改动数。 */
    gitRefreshKey: Int,
    sessionId: String,
    chatViewModel: ChatViewModel,
    /** 打开后定位到的工作区虚拟目录；不存在时定位到最近的已存在上级。 */
    initialFolderPath: String? = null,
    /** 从“Agent 可达范围”跳转 Tailscale 设置；先关闭资源管理器再导航。 */
    onOpenTailscaleSettings: (() -> Unit)? = null,
    viewModel: ResourceExplorerViewModel = viewModel(
        factory = ResourceExplorerViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val externalFolderManager = (LocalContext.current.applicationContext as? NexaraApplication)?.externalFolderManager
    DisposableEffect(sessionId, viewModel) {
        viewModel.loadSession(sessionId)
        onDispose { viewModel.deactivateSession() }
    }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recycleBinCount by viewModel.recycleBinCount.collectAsStateWithLifecycle()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsStateWithLifecycle()
    val rootFiles by viewModel.rootFiles.collectAsStateWithLifecycle()
    val recycledFiles by viewModel.recycledFiles.collectAsStateWithLifecycle()
    val recycleOperationState by viewModel.recycleOperationState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val importItems by viewModel.importItems.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val gitEntry by viewModel.gitEntry.collectAsStateWithLifecycle()
    val workspaceMode by viewModel.workspaceMode.collectAsStateWithLifecycle()
    val directory by viewModel.directory.collectAsStateWithLifecycle()
    var showRecycle by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showReach by rememberSaveable(sessionId) { mutableStateOf(false) }
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    // 与执行层一致：生成进行中或有待审批的操作时不允许切换工作区。
    val workspaceBusy = chatState.isGenerating || chatState.session?.approvalRequest != null
    var pickingWorkspace by rememberSaveable(sessionId) { mutableStateOf(false) }
    var workspaceSwitchFailed by remember(sessionId) { mutableStateOf(false) }
    val onWorkspaceSwitched: (WorkspaceSwitchResult) -> Unit = { result ->
        workspaceSwitchFailed = result is WorkspaceSwitchResult.Failed || result is WorkspaceSwitchResult.RejectedBusy
        if (result is WorkspaceSwitchResult.Switched) viewModel.retryLoadSession()
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    val initialGitRefreshKey = remember { gitRefreshKey }
    LaunchedEffect(gitRefreshKey) {
        if (gitRefreshKey != initialGitRefreshKey) viewModel.refreshGitEntry()
    }

    NexaraPageLayout(
        title = stringResource(R.string.resource_explorer_title),
        onBack = onBack,
        backCloses = LocalNexaraPanePresentation.current == NexaraPanePresentation.Pane,
        scrollable = false,
        bodyPadding = PaddingValues(
            start = NexaraSpacing.ScreenHorizontal,
            end = NexaraSpacing.ScreenHorizontal,
            bottom = NexaraSpacing.Large,
        ),
    ) {
        ResourceExplorerSheetContent(
            state = ResourceExplorerSheetState(
                showRecycle = showRecycle,
                searchQuery = searchQuery,
                recycleBinCount = recycleBinCount,
                workspaceReady = workspaceRootUuid != null,
                importItems = importItems,
                isImporting = isImporting,
                isLoading = isLoading,
                hasLoadError = loadError != null,
                gitEntry = gitEntry,
                workspaceMode = workspaceMode,
                workspaceSwitchBusy = workspaceBusy,
                workspaceSwitchFailed = workspaceSwitchFailed,
            ),
            actions = ResourceExplorerSheetActions(
                onOpenGitPanel = onOpenGit,
                onOpenReach = { showReach = true },
                onChooseWorkspace = { pickingWorkspace = true },
                onSearchQueryChanged = viewModel::updateSearchQuery,
                onImportFiles = {
                    filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray())
                },
                onRetryImport = viewModel::retryImport,
                onClearImportResults = viewModel::clearImportResults,
                onOpenRecycle = { showRecycle = true },
                onCloseRecycle = { showRecycle = false },
                onRetryLoad = viewModel::retryLoadSession,
            ),
            filesContent = {
                val directoryMode = workspaceMode as? ResourceExplorerWorkspaceMode.Directory
                if (directoryMode != null) {
                    // 目录工作区内的路径以目录根为 `/`，与 Agent 和媒体解析器看到的虚拟路径一致。
                    val openMedia = rememberWorkspaceMediaOpener(sessionId, directoryMode.displayName)
                    DirectoryWorkspaceBrowser(
                        rootTitle = directoryMode.displayName,
                        state = directory,
                        extraActions = { ResourceExplorerRecycleAction(count = null, onClick = { showRecycle = true }) },
                        onNavigate = viewModel::openDirectory,
                        onOpenFile = viewModel::openDirectoryFile,
                        onCloseFile = viewModel::closeDirectoryFile,
                        onRefresh = viewModel::refreshDirectory,
                        readFile = viewModel::readDirectoryFile,
                        onOpenMedia = openMedia?.let { open -> { entry -> open(entry.path) } },
                        fileActions = remember(viewModel) {
                            DirectoryFileActions(
                                onToggleSelect = viewModel::toggleDirectorySelection,
                                onClearSelection = viewModel::clearDirectorySelection,
                                onCopy = viewModel::copyDirectorySelection,
                                onPaste = viewModel::pasteDirectoryClipboard,
                                onCancelClipboard = viewModel::clearDirectoryClipboard,
                                onDelete = viewModel::deleteDirectorySelection,
                                onCreateFolder = viewModel::createDirectoryFolder,
                                onRename = viewModel::renameDirectoryEntry,
                                onDismissOutcome = viewModel::dismissDirectoryOutcome,
                                onDeletePermanently = viewModel::deleteTooLargePermanently,
                                onKeepTooLarge = viewModel::keepTooLarge,
                            )
                        },
                        rootHeader = {
                            // 目录工作区下 /mnt 挂载仍对 Agent 生效，其回收区也承接目录内被删除文件的备份。
                            externalFolderManager?.let { manager ->
                                ExternalFoldersPanel(
                                    sessionId = sessionId,
                                    workspaceRootUuid = null,
                                    manager = manager,
                                    onReindex = null,
                                )
                            }
                        },
                    )
                } else WorkspaceFolderBrowser(
                    workspaceRootUuid = workspaceRootUuid,
                    workspaceRepo = viewModel.workspaceRepo,
                    sessionId = sessionId,
                    externalFolderManager = externalFolderManager,
                    searchQuery = searchQuery,
                    rootFiles = rootFiles,
                    isLoading = isLoading,
                    hasLoadError = loadError != null,
                    onRetryLoad = viewModel::retryLoadSession,
                    onClearSearch = { viewModel.updateSearchQuery("") },
                    initialFolderPath = initialFolderPath,
                    onImport = { filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) }
                        .takeIf { workspaceRootUuid != null && !isImporting },
                )
            },
            recycleBinContent = {
                RecycleBinPanel(
                    files = recycledFiles,
                    operationState = recycleOperationState,
                    onRestoreFiles = viewModel::restoreRecycledFiles,
                    onPermanentlyDeleteFiles = viewModel::permanentlyDeleteRecycledFiles,
                    onEmptyRecycleBin = viewModel::emptyRecycleBin,
                    onRetryOperation = viewModel::retryFailedRecycleOperation,
                    onClearOperationState = viewModel::clearRecycleOperationState,
                )
            },
            directoryRecycleContent = {
                if (externalFolderManager != null && workspaceMode is ResourceExplorerWorkspaceMode.Directory) {
                    DirectoryRecycleList(sessionId, externalFolderManager, onRestored = viewModel::refreshDirectory)
                } else {
                    DirectoryRecycleInfo()
                }
            },
        )
    }
    WorkspacePickerSheet(
        show = pickingWorkspace && !workspaceBusy,
        onDismiss = { pickingWorkspace = false },
        currentWorkspaceId = chatState.session?.workspaceId,
        onPicked = { picked ->
            pickingWorkspace = false
            chatViewModel.switchSessionWorkspace(picked.id, onWorkspaceSwitched)
        },
        onPickPrivate = {
            pickingWorkspace = false
            if (chatState.session?.workspaceId != null) chatViewModel.switchSessionWorkspace(null, onWorkspaceSwitched)
        },
    )
    if (showReach) {
        AgentReachSheet(
            sessionId = sessionId,
            executionMode = chatState.session?.executionMode,
            onDismiss = { showReach = false },
            onOpenTailscaleSettings = onOpenTailscaleSettings,
        )
    }
}

internal data class ResourceExplorerSheetState(
    /** 回收站是当前工作区的子页，从文件页工具栏进入。 */
    val showRecycle: Boolean = false,
    val searchQuery: String = "",
    val recycleBinCount: Int = 0,
    val workspaceReady: Boolean = false,
    val importItems: List<ShareImportItem> = emptyList(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
    /** 会话工作区是 Git 仓库时显示分支与改动数入口。 */
    val gitEntry: GitEntrySummary = GitEntrySummary.Hidden,
    /** 文件区展示的工作区；目录与不可用状态下停用搜索、导入与会话回收站。 */
    val workspaceMode: ResourceExplorerWorkspaceMode = ResourceExplorerWorkspaceMode.Private,
    /** 生成进行中或有待审批时停用工作区切换。 */
    val workspaceSwitchBusy: Boolean = false,
    val workspaceSwitchFailed: Boolean = false,
)

internal data class ResourceExplorerSheetActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onImportFiles: () -> Unit = {},
    val onRetryImport: (android.net.Uri) -> Unit = {},
    val onClearImportResults: () -> Unit = {},
    val onOpenRecycle: () -> Unit = {},
    val onCloseRecycle: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onOpenGitPanel: () -> Unit = {},
    val onChooseWorkspace: () -> Unit = {},
    val onOpenReach: (() -> Unit)? = null,
)

@Composable
internal fun ResourceExplorerSheetContent(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
    recycleBinContent: @Composable () -> Unit,
    directoryRecycleContent: @Composable () -> Unit = { DirectoryRecycleInfo() },
) {
    BackHandler(enabled = state.showRecycle, onBack = actions.onCloseRecycle)
    AnimatedContent(
        targetState = state.showRecycle,
        transitionSpec = { hierarchicalTransition { if (it) 1 else 0 } },
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_ROOT),
        label = "resource_explorer_page",
    ) { recycle ->
        if (recycle) {
            ResourceExplorerRecyclePage(state, actions, recycleBinContent, directoryRecycleContent)
        } else {
            ResourceExplorerFilesPage(state = state, actions = actions, filesContent = filesContent)
        }
    }
}

@Composable
private fun ResourceExplorerRecyclePage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    recycleBinContent: @Composable () -> Unit,
    directoryRecycleContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_PAGE)) {
        NexaraNavigationHeader(
            title = stringResource(R.string.resource_explorer_recycle_bin),
            onBack = actions.onCloseRecycle,
            backButtonTag = UiTags.RESOURCE_EXPLORER_RECYCLE_BACK,
            modifier = Modifier.padding(horizontal = NexaraSpacing.Small),
        )
        // 目录工作区说明、加载、失败与回收站列表之间交叉淡入。
        val recyclePhase = when {
            state.workspaceMode is ResourceExplorerWorkspaceMode.Directory ||
                state.workspaceMode is ResourceExplorerWorkspaceMode.Unavailable -> 0
            state.isLoading -> 1
            state.hasLoadError -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = recyclePhase,
            transitionSpec = { NexaraMotion.fadeThrough() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "resource_explorer_recycle_phase",
        ) { phase ->
            when (phase) {
                0 -> directoryRecycleContent()
                1 -> ResourceExplorerSessionState(isLoading = true)
                2 -> ResourceExplorerSessionState(
                    isLoading = false,
                    onRetry = actions.onRetryLoad,
                )
                else -> recycleBinContent()
            }
        }
    }
}

/** 工具栏中的回收站入口；[count] 为 null 时不显示数量。 */
@Composable
internal fun ResourceExplorerRecycleAction(count: Int?, onClick: () -> Unit) {
    val label = if (count != null && count > 0) {
        stringResource(R.string.resource_explorer_recycle_bin_count, count)
    } else {
        stringResource(R.string.resource_explorer_recycle_bin)
    }
    IconButton(onClick = onClick, modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_RECYCLE_ENTRY)) {
        BadgedBox(badge = { if (count != null && count > 0) Badge { Text(count.toString()) } }) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = label)
        }
    }
}

@Composable
private fun ResourceExplorerFilesPage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
) {
    when (val mode = state.workspaceMode) {
        ResourceExplorerWorkspaceMode.Resolving -> ResourceExplorerSessionState(
            isLoading = state.isLoading,
            onRetry = actions.onRetryLoad.takeIf { state.hasLoadError },
        )
        ResourceExplorerWorkspaceMode.Private -> ResourceExplorerPrivateFilesPage(state, actions, filesContent)
        is ResourceExplorerWorkspaceMode.Directory -> Column(modifier = Modifier.fillMaxSize()) {
            ResourceExplorerWorkspaceBar(state, actions)
            ResourceExplorerGitEntry(state, actions)
            Spacer(modifier = Modifier.height(NexaraSpacing.Small))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                filesContent()
            }
        }
        is ResourceExplorerWorkspaceMode.Unavailable -> Column(modifier = Modifier.fillMaxSize()) {
            ResourceExplorerWorkspaceBar(state, actions)
            WorkspaceUnavailableState(mode, onRetry = actions.onRetryLoad)
            ResourceExplorerGitEntry(state, actions)
        }
    }
}

@Composable
private fun ResourceExplorerGitEntry(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    // 收起期间沿用最后一次可见的摘要，避免入口在动画中途变空。
    val shownEntry = remember { arrayOfNulls<GitEntrySummary>(1) }
    if (state.gitEntry != GitEntrySummary.Hidden) shownEntry[0] = state.gitEntry
    AnimatedVisibility(
        visible = state.gitEntry != GitEntrySummary.Hidden,
        enter = NexaraMotion.expandIn,
        exit = NexaraMotion.shrinkOut,
    ) {
        Column {
            Spacer(modifier = Modifier.height(NexaraSpacing.Small))
            shownEntry[0]?.let { GitWorkspaceEntry(summary = it, onClick = actions.onOpenGitPanel) }
        }
    }
}

/** 文件页顶部的工作区栏：显示当前工作区并提供切换，取代原先在会话设置中的工作区项。 */
@Composable
private fun ResourceExplorerWorkspaceBar(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    SessionWorkspaceBar(
        workspace = state.workspaceMode.toWorkspaceUi(),
        busy = state.workspaceSwitchBusy,
        switchFailed = state.workspaceSwitchFailed,
        onChoose = actions.onChooseWorkspace,
        onOpenReach = actions.onOpenReach,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun ResourceExplorerWorkspaceMode.toWorkspaceUi(): SessionWorkspaceUi = when (this) {
    ResourceExplorerWorkspaceMode.Resolving -> SessionWorkspaceUi.Loading
    ResourceExplorerWorkspaceMode.Private -> SessionWorkspaceUi.Private
    is ResourceExplorerWorkspaceMode.Directory -> SessionWorkspaceUi.Directory(workspaceId, displayName, kind, location)
    is ResourceExplorerWorkspaceMode.Unavailable -> SessionWorkspaceUi.Unavailable(workspaceId, displayName, kind, location, reason)
}

@Composable
private fun WorkspaceUnavailableState(
    mode: ResourceExplorerWorkspaceMode.Unavailable,
    onRetry: () -> Unit,
) {
    val reason = stringResource(R.string.workspace_unavailable_reason, mode.reason)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.RESOURCE_EXPLORER_WORKSPACE_UNAVAILABLE)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                stateDescription = reason
            }
            .padding(horizontal = NexaraSpacing.XLarge, vertical = NexaraSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        // 名称与原因已在工作区栏中显示，这里只保留说明与重试；无障碍仍即时播报原因。
        Text(
            text = stringResource(R.string.resource_explorer_unavailable_help),
            style = NexaraTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .defaultMinSize(minWidth = NexaraSpacing.MinimumTouchTarget, minHeight = NexaraSpacing.MinimumTouchTarget)
                .testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
        ) {
            Text(stringResource(R.string.shared_btn_retry))
        }
    }
}

@Composable
private fun DirectoryRecycleInfo() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_DIRECTORY_RECYCLE_INFO)
            .padding(NexaraSpacing.XXLarge),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = stringResource(R.string.resource_explorer_directory_recycle_info),
            style = NexaraTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResourceExplorerPrivateFilesPage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ResourceExplorerWorkspaceBar(state, actions)
        Spacer(modifier = Modifier.height(NexaraSpacing.Small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
        ) {
            ResourceExplorerSearch(state, actions, Modifier.weight(1f))
            ResourceExplorerImportAction(state, actions)
            ResourceExplorerRecycleAction(count = state.recycleBinCount, onClick = actions.onOpenRecycle)
        }

        val shownImports = remember { arrayOfNulls<List<ShareImportItem>>(1) }
        if (state.importItems.isNotEmpty()) shownImports[0] = state.importItems
        AnimatedVisibility(visible = state.importItems.isNotEmpty(), enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
            Column {
                Spacer(modifier = Modifier.height(NexaraSpacing.Small))
                ImportStatusList(
                    items = shownImports[0].orEmpty(),
                    isImporting = state.isImporting,
                    onRetry = actions.onRetryImport,
                    onClear = actions.onClearImportResults,
                )
            }
        }

        ResourceExplorerGitEntry(state, actions)

        Spacer(modifier = Modifier.height(NexaraSpacing.Small))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            filesContent()
        }
    }
}

@Composable
private fun ResourceExplorerSearch(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    modifier: Modifier,
) {
    NexaraSearchBar(
        value = state.searchQuery,
        onValueChange = actions.onSearchQueryChanged,
        placeholder = stringResource(R.string.resource_explorer_search_placeholder),
        modifier = modifier.testTag(UiTags.RESOURCE_EXPLORER_SEARCH),
    )
}

@Composable
private fun ResourceExplorerImportAction(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    IconButton(
        onClick = actions.onImportFiles,
        enabled = state.workspaceReady && !state.isImporting,
        modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_IMPORT),
    ) {
        if (state.isImporting) {
            val importing = stringResource(R.string.resource_explorer_importing)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = importing },
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Rounded.UploadFile, contentDescription = stringResource(R.string.resource_explorer_import_files))
        }
    }
}

@Composable
private fun ResourceExplorerSessionState(
    isLoading: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val message = stringResource(
        if (isLoading) R.string.shared_loading
        else R.string.resource_explorer_error_workspace_load_failed,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .semantics {
                liveRegion = if (isLoading) LiveRegionMode.Polite else LiveRegionMode.Assertive
                stateDescription = message
            }
            .padding(NexaraSpacing.XXLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(NexaraSpacing.Medium))
        }
        Text(
            text = message,
            style = NexaraTypography.bodyMedium,
            color = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (!isLoading && onRetry != null) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .defaultMinSize(minWidth = NexaraSpacing.MinimumTouchTarget, minHeight = NexaraSpacing.MinimumTouchTarget)
                    .testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
            ) {
                Text(stringResource(R.string.shared_btn_retry))
            }
        }
    }
}

@Composable
private fun ImportStatusList(
    items: List<ShareImportItem>,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.resource_explorer_import_status),
                    style = NexaraTypography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClear,
                    enabled = !isImporting,
                    modifier = Modifier
                        .defaultMinSize(minWidth = NexaraSpacing.MinimumTouchTarget, minHeight = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS),
                ) {
                    Text(
                        stringResource(R.string.resource_explorer_clear_results),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    ImportStatusRow(
                        item = item,
                        isImporting = isImporting,
                        onRetry = onRetry,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ImportStatusRow(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val statusText = when (item.status) {
        ShareImportStatus.Pending -> stringResource(R.string.resource_explorer_status_pending)
        ShareImportStatus.Importing -> stringResource(R.string.resource_explorer_status_importing)
        ShareImportStatus.Created -> stringResource(R.string.resource_explorer_status_created)
        ShareImportStatus.Rejected -> stringResource(
            R.string.resource_explorer_status_rejected,
            rejectReasonLabel(item.reason),
        )
    }
    val statusColor = if (item.status == ShareImportStatus.Rejected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.Small)) {
        val stack = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.5f
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
            ) {
                Icon(
                    imageVector = when (item.status) {
                        ShareImportStatus.Pending, ShareImportStatus.Importing -> Icons.Rounded.HourglassTop
                        ShareImportStatus.Created -> Icons.Rounded.CheckCircle
                        ShareImportStatus.Rejected -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
                    NexaraIdentifierText(
                        text = item.displayName,
                        style = NexaraTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = statusText,
                        style = NexaraTypography.bodySmall,
                        color = statusColor,
                        modifier = Modifier.testTag(
                            UiTags.resourceExplorerImportStatus(item.uri.toString()),
                        ),
                    )
                }
            }
        }

        if (stack) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
                content(Modifier.fillMaxWidth())
                if (shouldOfferImportRetry(item)) {
                    ImportRetryAction(item, isImporting, onRetry, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                content(Modifier.weight(1f))
                if (shouldOfferImportRetry(item)) {
                    ImportRetryAction(item, isImporting, onRetry, Modifier)
                }
            }
        }
    }
}

@Composable
private fun ImportRetryAction(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    modifier: Modifier,
) {
    val actionLabel = stringResource(R.string.resource_explorer_retry_document, item.displayName)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = NexaraSpacing.MinimumTouchTarget, minHeight = NexaraSpacing.MinimumTouchTarget)
            .clickable(
                enabled = !isImporting,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = { onRetry(item.uri) },
            )
            .testTag(UiTags.resourceExplorerImportRetry(item.uri.toString()))
            .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.shared_btn_retry),
            style = NexaraTypography.labelLarge,
            color = if (isImporting) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason?): String = stringResource(
    when (reason) {
        ShareRejectReason.TargetRequired -> R.string.share_import_reason_target
        ShareRejectReason.UnsupportedMime -> R.string.share_import_reason_mime
        ShareRejectReason.MimeMismatch -> R.string.share_import_reason_mismatch
        ShareRejectReason.InvalidName -> R.string.share_import_reason_name
        ShareRejectReason.PermissionDenied -> R.string.share_import_reason_permission
        ShareRejectReason.EmptyFile -> R.string.share_import_reason_empty
        ShareRejectReason.ItemTooLarge -> R.string.share_import_reason_item_large
        ShareRejectReason.BatchTooLarge -> R.string.share_import_reason_batch_large
        ShareRejectReason.ReadFailed -> R.string.share_import_reason_read
        ShareRejectReason.WriteFailed -> R.string.share_import_reason_write
        ShareRejectReason.IndexScheduleFailed -> R.string.share_import_reason_index
        null -> R.string.resource_explorer_reason_unknown
    },
)

internal fun shouldOfferImportRetry(item: ShareImportItem): Boolean =
    item.status == ShareImportStatus.Rejected && item.reason?.retryable == true
