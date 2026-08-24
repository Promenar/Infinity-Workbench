package com.promenar.nexara.ui.rag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.ui.chat.components.FileBatchOperationResult
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import java.text.SimpleDateFormat

internal enum class PortalTab { DOCUMENTS, MEMORY, GRAPH }

internal fun ragIndexFallbackStatusResource(canRetryPendingIndex: Boolean): Int =
    if (canRetryPendingIndex) R.string.rag_index_retry_hint else R.string.rag_index_phase_unknown

internal fun shouldShowRagIndexActions(
    hasNotice: Boolean,
    canRetryPendingIndex: Boolean,
): Boolean = hasNotice || canRetryPendingIndex

internal enum class RagHomeIndexAction { RetryFailed, RetryPending, None }

internal fun resolveRagHomeIndexAction(
    noticeCode: String?,
    canRetryLastFailedIndex: Boolean,
    canRetryPendingIndex: Boolean,
): RagHomeIndexAction = when {
    noticeCode in setOf(IndexingNotice.CODE_FAILED, IndexingNotice.CODE_PARTIAL) &&
        canRetryLastFailedIndex -> RagHomeIndexAction.RetryFailed
    noticeCode != null -> RagHomeIndexAction.None
    canRetryPendingIndex -> RagHomeIndexAction.RetryPending
    else -> RagHomeIndexAction.None
}

internal fun shouldShowRagIndexSection(
    isIndexing: Boolean,
    hasNotice: Boolean,
    canRetryPendingIndex: Boolean,
): Boolean = isIndexing || hasNotice || canRetryPendingIndex

internal data class RagHomeScreenState(
    val currentTab: PortalTab,
    val searchQuery: String,
    val searchState: RagSearchUiState = RagSearchUiState.Idle,
    val selectedIds: MutableList<String>,
    val workspaceRootUuid: String?,
    val folders: List<Folder>,
    val folderStats: Map<String, Int>,
    val stats: RagStats,
    val memoryVectors: List<MemoryVectorRecord>,
    val isIndexing: Boolean,
    val indexingProgress: Float,
    val indexingNotice: com.promenar.nexara.ui.common.status.UiStatusNotice? = null,
    val canRetryLastFailedIndex: Boolean,
    val isRetryingLastFailedIndex: Boolean,
    val indexingFileIds: Set<String>,
    val kgExtractionStates: Map<String, KgStatus>,
    val canRetryPendingRenameIndex: Boolean = false,
    val isRetryingPendingRenameIndex: Boolean = false,
)

internal data class RagHomeScreenActions(
    val onSearch: (String) -> Unit = {},
    val onChangeTab: (PortalTab) -> Unit = {},
    val onLoadMemory: () -> Unit = {},
    val onOpenConfig: () -> Unit = {},
    val onOpenGraph: () -> Unit = {},
    val onOpenFilePicker: () -> Unit = {},
    val onCreateFolder: (String) -> Unit = {},
    val onRetryLastFailedIndex: () -> Unit = {},
    val onRetryPendingRenameIndex: () -> Unit = {},
    val onDismissQueueError: () -> Unit = {},
    val onNavigateToDocEditor: (String, String) -> Unit = { _, _ -> },
    val onReindexFile: (String) -> Unit = {},
    val onReindexDocuments: (Collection<String>) -> Unit = {},
    val onDeleteDocuments: (Collection<String>, (Boolean, List<String>) -> Unit) -> Unit = { _, _ -> },
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onMoveFile: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val onMoveDocuments: (Collection<String>, String, (Boolean, List<String>) -> Unit) -> Unit = { _, _, _ -> },
    val onExtractKG: (String) -> Unit = {},
    val onCopyFile: (String) -> Unit = {},
    val onDeleteMemory: (String) -> Unit = {},
)

private data class PendingDocumentDelete(
    val ids: List<String>,
    val completion: (FileBatchOperationResult) -> Unit,
)

/**
 * 将批量操作结果同步回选择态：成功项移除，失败项继续保留以便用户重试。
 * 移动与删除共用同一语义，避免任一失败项被错误清空。
 */
fun applyBatchSelectionResult(
    selectedIds: MutableList<String>,
    movingIds: Set<String>,
    failedIds: Set<String>,
    succeeded: Boolean,
): Boolean {
    selectedIds.removeAll(movingIds)
    if (!succeeded) {
        val retainedIds = failedIds.ifEmpty { movingIds }
        retainedIds.filterNotTo(selectedIds) { it in selectedIds }
    }
    return succeeded
}

/** 保留给现有文件夹移动流程的语义化兼容入口。 */
fun applyMoveSelectionResult(
    selectedIds: MutableList<String>,
    movingIds: Set<String>,
    failedIds: Set<String>,
    succeeded: Boolean,
): Boolean = applyBatchSelectionResult(selectedIds, movingIds, failedIds, succeeded)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagHomeScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as android.app.Application)),
    onNavigateToFolder: (String, String) -> Unit = { _, _ -> },
    onNavigateToConfig: () -> Unit = {},
    onNavigateToGraph: () -> Unit = {},
    onNavigateToDocEditor: (String, String) -> Unit = { _, _ -> },
) {
    val stats by viewModel.stats.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val folderStats by viewModel.folderStats.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val indexingNotice by viewModel.indexingNotice.collectAsState()
    val canRetryLastFailedIndex by viewModel.canRetryLastFailedIndex.collectAsState()
    val isRetryingLastFailedIndex by viewModel.isRetryingLastFailedIndex.collectAsState()
    val pendingRenameIndexTargets by viewModel.pendingRenameIndexTargets.collectAsState()
    val isRetryingPendingRenameIndex by viewModel.isRetryingPendingRenameIndex.collectAsState()
    val memoryVectors by viewModel.memoryVectors.collectAsState()
    val kgExtractionStates by viewModel.kgExtractionStates.collectAsState()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val indexingFileIds by viewModel.indexingDocIds.collectAsState()
    val searchState by viewModel.searchState.collectAsState()

    var currentTab by rememberSaveable { mutableStateOf(PortalTab.DOCUMENTS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }

    val routeState = remember(
        currentTab,
        searchQuery,
        searchState,
        selectedIds,
        workspaceRootUuid,
        folders,
        folderStats,
        stats,
        memoryVectors,
        isIndexing,
        indexingProgress,
        indexingNotice,
        canRetryLastFailedIndex,
        isRetryingLastFailedIndex,
        pendingRenameIndexTargets,
        isRetryingPendingRenameIndex,
        indexingFileIds,
        kgExtractionStates,
    ) {
        RagHomeScreenState(
            currentTab = currentTab,
            searchQuery = searchQuery,
            searchState = searchState,
            selectedIds = selectedIds,
            workspaceRootUuid = workspaceRootUuid,
            folders = folders,
            folderStats = folderStats,
            stats = stats,
            memoryVectors = memoryVectors,
            isIndexing = isIndexing,
            indexingProgress = indexingProgress,
            indexingNotice = indexingNotice,
            canRetryLastFailedIndex = canRetryLastFailedIndex,
            isRetryingLastFailedIndex = isRetryingLastFailedIndex,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            canRetryPendingRenameIndex = pendingRenameIndexTargets.isNotEmpty(),
            isRetryingPendingRenameIndex = isRetryingPendingRenameIndex,
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    val actions = RagHomeScreenActions(
        onSearch = { query ->
            searchQuery = query
            viewModel.search(query)
        },
        onChangeTab = { nextTab ->
            currentTab = nextTab
        },
        onLoadMemory = viewModel::loadMemoryVectors,
        onOpenConfig = onNavigateToConfig,
        onOpenGraph = onNavigateToGraph,
        onOpenFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) },
        onCreateFolder = viewModel::createFolder,
        onRetryLastFailedIndex = viewModel::retryLastFailedIndex,
        onRetryPendingRenameIndex = viewModel::retryPendingRenameIndex,
        onDismissQueueError = viewModel::dismissQueueError,
        onNavigateToDocEditor = onNavigateToDocEditor,
        onReindexFile = viewModel::reindexFile,
        onReindexDocuments = viewModel::reindexDocuments,
        onDeleteDocuments = { ids, onComplete ->
            viewModel.deleteDocuments(ids) { _, failedIds ->
                onComplete(
                    failedIds.isEmpty(),
                    failedIds.toList(),
                )
            }
        },
        onRenameFolder = viewModel::renameFolder,
        onMoveFile = viewModel::moveFile,
        onMoveDocuments = { ids, targetFolderId, onComplete ->
            viewModel.moveDocuments(ids, targetFolderId) { succeeded, failedIds ->
                onComplete(succeeded, failedIds.toList())
            }
        },
        onExtractKG = viewModel::extractKG,
        onCopyFile = viewModel::copyFile,
        onDeleteMemory = viewModel::deleteMemoryVector,
    )

    val documentsContent: @Composable (
        Modifier,
        MutableList<String>,
        (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit,
    ) -> Unit = remember(
        onNavigateToFolder,
        onNavigateToGraph,
        onNavigateToDocEditor,
        workspaceRootUuid,
        searchQuery,
        searchState,
        indexingFileIds,
        kgExtractionStates,
    ) {
        { modifier, selectedDocumentIds, requestDelete ->
            Box(modifier = modifier) {
                if (searchQuery.isBlank()) {
                    FilesPanel(
                        workspaceRootUuid = workspaceRootUuid,
                        workspaceRepo = viewModel.getWorkspaceRepo(),
                        searchQuery = "",
                        useScroll = true,
                        onReindex = { actions.onReindexFile(it) },
                        onDelete = requestDelete,
                        onRename = { uuid, name -> actions.onRenameFolder(uuid, name) },
                        onMove = { uuid, targetId -> actions.onMoveFile(uuid, targetId) {} },
                        onExtractKG = actions.onExtractKG,
                        onViewKG = { onNavigateToGraph() },
                        onCopy = actions.onCopyFile,
                        indexingFileIds = indexingFileIds,
                        kgExtractionStates = kgExtractionStates,
                        externalSelectedIds = selectedDocumentIds,
                        showSelectionOverlay = false,
                        onFolderClick = onNavigateToFolder,
                        onFileClick = { docId ->
                            workspaceRootUuid?.let { root ->
                                actions.onNavigateToDocEditor(root, docId)
                            }
                        },
                    )
                } else {
                    RagSearchResults(
                        state = searchState,
                        onOpenDocument = { docId ->
                            workspaceRootUuid?.let { root ->
                                actions.onNavigateToDocEditor(root, docId)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    RagHomeScreenContent(
        state = routeState,
        actions = actions,
        documentsContent = documentsContent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RagHomeScreenContent(
    state: RagHomeScreenState,
    actions: RagHomeScreenActions,
    documentsContent: @Composable (
        Modifier,
        MutableList<String>,
        (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit,
    ) -> Unit,
    initiallyExpandedMemoryId: String? = null,
    initialMemoryDeleteTargetId: String? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val sdf = remember(locale) { SimpleDateFormat("MMM d, HH:mm", locale) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var isMovingSelection by remember { mutableStateOf(false) }
    var isDeletingSelection by remember { mutableStateOf(false) }
    var pendingDocumentDelete by remember { mutableStateOf<PendingDocumentDelete?>(null) }
    var expandedMemoryId by remember(initiallyExpandedMemoryId) { mutableStateOf(initiallyExpandedMemoryId) }
    var memoryDeleteTargetId by remember(initialMemoryDeleteTargetId) {
        mutableStateOf(initialMemoryDeleteTargetId)
    }

    val requestDocumentDelete: (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit = { ids, completion ->
        val distinctIds = ids.distinct()
        if (distinctIds.isNotEmpty() && pendingDocumentDelete == null && !isDeletingSelection) {
            pendingDocumentDelete = PendingDocumentDelete(distinctIds, completion)
        }
    }

    LaunchedEffect(state.currentTab) {
        if (state.currentTab == PortalTab.MEMORY) actions.onLoadMemory()
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.RAG_HOME_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.rag_home_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                },
                actions = {
                    IconButton(
                        onClick = actions.onOpenGraph,
                        modifier = Modifier
                            .sizeIn(
                                minWidth = NexaraSpacing.MinimumTouchTarget,
                                minHeight = NexaraSpacing.MinimumTouchTarget,
                            )
                            .testTag(UiTags.RAG_HOME_TAB_GRAPH),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = stringResource(R.string.rag_home_graph),
                        )
                    }
                    IconButton(
                        onClick = actions.onOpenConfig,
                        modifier = Modifier
                            .sizeIn(
                                minWidth = NexaraSpacing.MinimumTouchTarget,
                                minHeight = NexaraSpacing.MinimumTouchTarget,
                            )
                            .testTag(UiTags.RAG_HOME_CONFIG),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.common_cd_config),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (state.currentTab == PortalTab.DOCUMENTS && state.selectedIds.isNotEmpty()) {
                RagHomeSelectionBar(
                    selectedCount = state.selectedIds.size,
                    operationsEnabled = !isMovingSelection && !isDeletingSelection,
                    onClear = state.selectedIds::clear,
                    onMove = { showMoveSheet = true },
                    onReindex = {
                        actions.onReindexDocuments(state.selectedIds.toList())
                        state.selectedIds.clear()
                    },
                    onDelete = {
                        requestDocumentDelete(state.selectedIds.toList()) { result ->
                            applyBatchSelectionResult(
                                selectedIds = state.selectedIds,
                                movingIds = result.attemptedIds.toSet(),
                                failedIds = result.failedIds.toSet(),
                                succeeded = result.attemptedIds.isNotEmpty() && result.failedIds.isEmpty(),
                            )
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = NexaraSpacing.ScreenHorizontal),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = if (state.currentTab == PortalTab.MEMORY) 1 else 0,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                ) {
                    listOf(PortalTab.DOCUMENTS, PortalTab.MEMORY).forEach { tab ->
                        Tab(
                            selected = state.currentTab == tab,
                            onClick = { actions.onChangeTab(tab) },
                            text = {
                                Text(
                                    text = stringResource(
                                        if (tab == PortalTab.DOCUMENTS) {
                                            R.string.rag_home_documents
                                        } else {
                                            R.string.rag_home_memory
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                            modifier = when (tab) {
                                PortalTab.DOCUMENTS -> Modifier.testTag(UiTags.RAG_HOME_TAB_DOCUMENTS)
                                PortalTab.MEMORY -> Modifier.testTag(UiTags.RAG_HOME_TAB_MEMORY)
                                PortalTab.GRAPH -> Modifier
                            }
                                .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget),
                        )
                    }
                }

                Spacer(Modifier.height(NexaraSpacing.Medium))

                AnimatedVisibility(
                    visible = shouldShowRagIndexSection(
                        isIndexing = state.isIndexing,
                        hasNotice = state.indexingNotice != null,
                        canRetryPendingIndex = state.canRetryPendingRenameIndex,
                    ),
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it },
                    exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { -it }
                ) {
                    Column(
                        modifier = Modifier
                            .testTag(UiTags.RAG_HOME_INDEXING_NOTICE)
                    ) {
                        val resolvedNotice = state.indexingNotice?.let { IndexingNotice.template(it) }
                        val statusText = resolvedNotice?.let { resolved ->
                            stringResource(resolved.resourceId, *resolved.args.toTypedArray())
                        } ?: stringResource(
                            ragIndexFallbackStatusResource(state.canRetryPendingRenameIndex),
                        )
                        val isError = state.indexingNotice?.severity == NoticeSeverity.Error
                        val indexAction = resolveRagHomeIndexAction(
                            noticeCode = state.indexingNotice?.code,
                            canRetryLastFailedIndex = state.canRetryLastFailedIndex,
                            canRetryPendingIndex = state.canRetryPendingRenameIndex,
                        )
                        val showRetry = indexAction != RagHomeIndexAction.None
                        val retrying = state.isRetryingLastFailedIndex ||
                            state.isRetryingPendingRenameIndex

                        if (shouldShowRagIndexActions(
                                hasNotice = state.indexingNotice != null,
                                canRetryPendingIndex = state.canRetryPendingRenameIndex,
                            )
                        ) {
                            Column {
                                IndexingProgressBar(
                                    progress = state.indexingProgress.coerceAtLeast(0f),
                                    statusText = statusText,
                                    subStatusText = if (showRetry && !retrying) {
                                        stringResource(R.string.rag_index_retry_hint)
                                    } else {
                                        null
                                    },
                                    isError = isError
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (showRetry) {
                                        TextButton(
                                            onClick = when (indexAction) {
                                                RagHomeIndexAction.RetryFailed -> actions.onRetryLastFailedIndex
                                                RagHomeIndexAction.RetryPending -> actions.onRetryPendingRenameIndex
                                                RagHomeIndexAction.None -> ({})
                                            },
                                            enabled = !retrying,
                                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                        ) {
                                            Text(stringResource(R.string.shared_btn_retry))
                                        }
                                    }
                                    TextButton(
                                        onClick = actions.onDismissQueueError,
                                        enabled = !retrying,
                                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                    ) {
                                        Text(stringResource(R.string.common_dismiss))
                                    }
                                }
                            }
                        } else if (state.isIndexing) {
                            val fallbackText = stringResource(R.string.rag_index_phase_unknown)
                            IndexingProgressBar(
                                progress = state.indexingProgress,
                                statusText = if (statusText.isNotBlank()) statusText else fallbackText,
                                subStatusText = null,
                                isError = false
                            )
                        }
                    }
                }

                when (state.currentTab) {
                    PortalTab.DOCUMENTS -> {
                        NexaraSearchBar(
                            value = state.searchQuery,
                            onValueChange = actions.onSearch,
                            placeholder = stringResource(R.string.rag_home_search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = NexaraSpacing.Small)
                                .testTag(UiTags.RAG_HOME_SEARCH),
                        )

                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = NexaraSpacing.Small),
                            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        ) {
                            FilledTonalButton(
                                onClick = actions.onOpenFilePicker,
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = NexaraSpacing.MinimumTouchTarget,
                                        minHeight = NexaraSpacing.MinimumTouchTarget,
                                    )
                                    .testTag(UiTags.RAG_HOME_UPLOAD),
                            ) {
                                Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(NexaraSpacing.Small))
                                Text(stringResource(R.string.rag_folder_upload))
                            }
                            TextButton(
                                onClick = { showNewFolderDialog = true },
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = NexaraSpacing.MinimumTouchTarget,
                                        minHeight = NexaraSpacing.MinimumTouchTarget,
                                    )
                                    .testTag(UiTags.RAG_HOME_NEW_FOLDER),
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(NexaraSpacing.Small))
                                Text(stringResource(R.string.rag_home_new_folder_title))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(UiTags.RAG_HOME_DOCUMENTS_CONTENT)
                        ) {
                            documentsContent(
                                Modifier.fillMaxSize(),
                                state.selectedIds,
                                requestDocumentDelete,
                            )
                        }

                    }

                    PortalTab.MEMORY -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(UiTags.RAG_HOME_MEMORY_CONTENT),
                            contentPadding = PaddingValues(bottom = NexaraSpacing.XLarge),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        ) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = NexaraSpacing.Small),
                                    verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
                                ) {
                                    Text(
                                        text = stringResource(R.string.rag_home_memory_section),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.rag_home_memory_total_count,
                                                state.memoryVectors.size,
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.rag_home_memory_est_tokens,
                                                state.memoryVectors.sumOf { it.content.length / 3 },
                                            ),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }

                            if (state.memoryVectors.isEmpty()) {
                                item {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("memory-empty"),
                                        shape = MaterialTheme.shapes.large,
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(NexaraSpacing.XXLarge),
                                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Icon(
                                                Icons.Rounded.Psychology,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(40.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_empty),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(state.memoryVectors, key = { it.id }) { memory ->
                                    val isExpanded = expandedMemoryId == memory.id
                                    ListItem(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .testTag("memory-item-${memory.id}"),
                                        headlineContent = {
                                            Text(
                                                text = memory.content,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        supportingContent = {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                                                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
                                            ) {
                                                memory.sessionId?.let {
                                                    Text(
                                                        "${stringResource(R.string.sessions_tag_session)}: ${it.take(8)}…",
                                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                                Text(
                                                    sdf.format(java.util.Date(memory.createdAt)),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        expandedMemoryId = if (isExpanded) null else memory.id
                                                    },
                                                    modifier = Modifier
                                                        .sizeIn(
                                                            minWidth = NexaraSpacing.MinimumTouchTarget,
                                                            minHeight = NexaraSpacing.MinimumTouchTarget,
                                                        )
                                                        .testTag("memory-expand-${memory.id}"),
                                                ) {
                                                    Icon(
                                                        imageVector = if (isExpanded) {
                                                            Icons.Rounded.ExpandLess
                                                        } else {
                                                            Icons.Rounded.ExpandMore
                                                        },
                                                        contentDescription = stringResource(
                                                            if (isExpanded) {
                                                                R.string.common_cd_collapse
                                                            } else {
                                                                R.string.common_cd_expand
                                                            },
                                                        ),
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { memoryDeleteTargetId = memory.id },
                                                    modifier = Modifier
                                                        .sizeIn(
                                                            minWidth = NexaraSpacing.MinimumTouchTarget,
                                                            minHeight = NexaraSpacing.MinimumTouchTarget,
                                                        )
                                                        .testTag("memory-delete-${memory.id}"),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Delete,
                                                        contentDescription = stringResource(R.string.common_cd_delete),
                                                        tint = MaterialTheme.colorScheme.error,
                                                    )
                                                }
                                            }
                                        },
                                        colors = ListItemDefaults.colors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    PortalTab.GRAPH -> {}
                }
            }
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = {
                Box(modifier = Modifier.testTag(UiTags.RAG_HOME_NEW_FOLDER_DIALOG)) {
                    Text(
                        stringResource(R.string.rag_home_new_folder_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.rag_home_folder_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotBlank()) actions.onCreateFolder(folderName.trim())
                        showNewFolderDialog = false
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.shared_btn_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMoveSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (!isMovingSelection) showMoveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.testTag(UiTags.RAG_HOME_MOVE_SHEET),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .testTag("rag-home-move-folder-list"),
                contentPadding = PaddingValues(
                    start = NexaraSpacing.Large,
                    top = NexaraSpacing.Medium,
                    end = NexaraSpacing.Large,
                    bottom = NexaraSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                item {
                    Text(
                        stringResource(R.string.rag_home_move_to_folder),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = NexaraSpacing.Small),
                    )
                }
                items(state.folders, key = { it.id }) { folder ->
                    val moveLabel = stringResource(R.string.rag_home_move)
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(
                                enabled = !isMovingSelection,
                                onClickLabel = moveLabel,
                                role = Role.Button,
                            ) {
                                val movingIds = state.selectedIds.toList()
                                if (movingIds.isNotEmpty()) {
                                    isMovingSelection = true
                                    actions.onMoveDocuments(movingIds, folder.id) { succeeded, failedIds ->
                                        isMovingSelection = false
                                        if (applyBatchSelectionResult(
                                                selectedIds = state.selectedIds,
                                                movingIds = movingIds.toSet(),
                                                failedIds = failedIds.toSet(),
                                                succeeded = succeeded,
                                            )
                                        ) {
                                            showMoveSheet = false
                                        }
                                    }
                                }
                            },
                        headlineContent = {
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.rag_home_docs_count, state.folderStats[folder.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }
        }
    }

    pendingDocumentDelete?.let { pendingDelete ->
        val dismissDeleteConfirmation = {
            pendingDelete.completion(
                FileBatchOperationResult(
                    attemptedIds = emptyList(),
                    failedIds = emptyList(),
                ),
            )
            pendingDocumentDelete = null
        }
        AlertDialog(
            onDismissRequest = { if (!isDeletingSelection) dismissDeleteConfirmation() },
            modifier = Modifier.testTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG),
            title = {
                Text(
                    stringResource(R.string.rag_home_delete_confirm_title, pendingDelete.ids.size),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pendingDelete.ids.isNotEmpty()) {
                            isDeletingSelection = true
                            actions.onDeleteDocuments(pendingDelete.ids) { succeeded, failedIds ->
                                val normalizedFailedIds = if (!succeeded && failedIds.isEmpty()) {
                                    pendingDelete.ids
                                } else {
                                    failedIds
                                }
                                pendingDelete.completion(
                                    FileBatchOperationResult(
                                        attemptedIds = pendingDelete.ids,
                                        failedIds = normalizedFailedIds,
                                    ),
                                )
                                isDeletingSelection = false
                                pendingDocumentDelete = if (normalizedFailedIds.isEmpty()) {
                                    null
                                } else {
                                    pendingDelete.copy(ids = normalizedFailedIds.distinct())
                                }
                            }
                        }
                    },
                    enabled = pendingDelete.ids.isNotEmpty() && !isDeletingSelection,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON),
                ) {
                    Text(stringResource(R.string.shared_btn_move_to_recycle_bin))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = dismissDeleteConfirmation,
                    enabled = !isDeletingSelection,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (memoryDeleteTargetId != null) {
        AlertDialog(
            onDismissRequest = { memoryDeleteTargetId = null },
            modifier = Modifier.testTag("memory-delete-dialog"),
            title = {
                Text(
                    stringResource(R.string.rag_home_memory_delete_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    stringResource(R.string.rag_home_memory_delete_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        memoryDeleteTargetId?.let(actions.onDeleteMemory)
                        memoryDeleteTargetId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .sizeIn(
                            minWidth = NexaraSpacing.MinimumTouchTarget,
                            minHeight = NexaraSpacing.MinimumTouchTarget,
                        )
                        .testTag("memory-delete-confirm"),
                ) {
                    Text(stringResource(R.string.shared_btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDeleteTargetId = null }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RagHomeSelectionBar(
    selectedCount: Int,
    operationsEnabled: Boolean,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = NexaraSpacing.ScreenHorizontal)
                .padding(bottom = NexaraSpacing.Medium)
                .testTag(UiTags.RAG_HOME_SELECTION_BAR),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NexaraSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rag_home_selected_count, selectedCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onClear,
                        enabled = operationsEnabled,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.RAG_HOME_CLEAR_SELECTION),
                    ) {
                        Text(stringResource(R.string.rag_home_clear_all))
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onMove,
                        enabled = operationsEnabled,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.RAG_HOME_MOVE_SELECTION),
                    ) {
                        Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.rag_home_move))
                    }
                    TextButton(
                        onClick = onReindex,
                        enabled = operationsEnabled,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.RAG_HOME_REINDEX_SELECTION),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.rag_home_reindex))
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = operationsEnabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.RAG_HOME_DELETE_SELECTION),
                    ) {
                        Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.shared_btn_delete))
                    }
                }
            }
        }
    }
}
