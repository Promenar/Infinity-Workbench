package com.promenar.nexara.ui.rag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.ui.chat.components.FileBatchOperationResult
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat

internal enum class PortalTab { DOCUMENTS, MEMORY, GRAPH }

internal data class RagHomeScreenState(
    val currentTab: PortalTab,
    val searchQuery: String,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val memoryVectors by viewModel.memoryVectors.collectAsState()
    val kgExtractionStates by viewModel.kgExtractionStates.collectAsState()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val indexingFileIds by viewModel.indexingDocIds.collectAsState()

    var currentTab by rememberSaveable { mutableStateOf(PortalTab.DOCUMENTS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }

    val routeState = remember(
        currentTab,
        searchQuery,
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
        indexingFileIds,
        kgExtractionStates,
    ) {
        RagHomeScreenState(
            currentTab = currentTab,
            searchQuery = searchQuery,
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
    ) -> Unit = remember(onNavigateToFolder, onNavigateToGraph, workspaceRootUuid, searchQuery, indexingFileIds, kgExtractionStates) {
        { modifier, selectedDocumentIds, requestDelete ->
            Box(modifier = modifier) {
                FilesPanel(
                    workspaceRootUuid = workspaceRootUuid,
                    workspaceRepo = viewModel.getWorkspaceRepo(),
                    searchQuery = searchQuery,
                    useScroll = true,
                    onReindex = { actions.onReindexFile(it) },
                    onDelete = requestDelete,
                    onRename = { uuid, name ->
                        actions.onRenameFolder(uuid, name)
                    },
                    onMove = { uuid, targetId ->
                        actions.onMoveFile(uuid, targetId) {}
                    },
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
            }
        }
    }

    RagHomeScreenContent(
        state = routeState,
        actions = actions,
        documentsContent = documentsContent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RagHomeScreenContent(
    state: RagHomeScreenState,
    actions: RagHomeScreenActions,
    documentsContent: @Composable (
        Modifier,
        MutableList<String>,
        (Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit,
    ) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val sdf = remember(locale) { SimpleDateFormat("MMM d, HH:mm", locale) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var isMovingSelection by remember { mutableStateOf(false) }
    var isDeletingSelection by remember { mutableStateOf(false) }
    var pendingDocumentDelete by remember { mutableStateOf<PendingDocumentDelete?>(null) }
    var expandedMemoryId by remember { mutableStateOf<String?>(null) }
    var memoryDeleteTarget by remember { mutableStateOf<MemoryVectorRecord?>(null) }

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
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.rag_home_title),
                        style = NexaraTypography.headlineLarge,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
                actions = {
                    IconButton(
                        onClick = actions.onOpenConfig,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.RAG_HOME_CONFIG),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.common_cd_config),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            ) {
                NexaraSearchBar(
                    value = state.searchQuery,
                    onValueChange = { actions.onSearch(it) },
                    placeholder = stringResource(R.string.rag_home_search),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).testTag(UiTags.RAG_HOME_SEARCH),
                )

                TabRow(
                    selectedTabIndex = state.currentTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = NexaraColors.Primary,
                    divider = { HorizontalDivider(color = NexaraColors.OutlineVariant) }
                ) {
                    listOf(
                        PortalTab.DOCUMENTS to (Icons.Rounded.Description to stringResource(R.string.rag_home_documents)),
                        PortalTab.MEMORY to (Icons.Rounded.Psychology to stringResource(R.string.rag_home_memory)),
                        PortalTab.GRAPH to (Icons.Rounded.AccountTree to stringResource(R.string.rag_home_graph)),
                    ).forEach { (tab, data) ->
                        Tab(
                            selected = state.currentTab == tab,
                            onClick = {
                                if (tab == PortalTab.GRAPH) {
                                    actions.onOpenGraph()
                                } else {
                                    actions.onChangeTab(tab)
                                }
                            },
                            text = { Text(data.second, style = NexaraTypography.labelMedium) },
                            selectedContentColor = NexaraColors.Primary,
                            unselectedContentColor = NexaraColors.OnSurfaceVariant,
                            modifier = when (tab) {
                                PortalTab.DOCUMENTS -> Modifier.testTag(UiTags.RAG_HOME_TAB_DOCUMENTS)
                                PortalTab.MEMORY -> Modifier.testTag(UiTags.RAG_HOME_TAB_MEMORY)
                                PortalTab.GRAPH -> Modifier.testTag(UiTags.RAG_HOME_TAB_GRAPH)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                AnimatedVisibility(
                    visible = state.isIndexing || state.indexingNotice != null,
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
                        } ?: stringResource(R.string.rag_index_phase_unknown)
                        val isError = state.indexingNotice?.severity == NoticeSeverity.Error
                        val showRetry = state.canRetryLastFailedIndex && state.indexingNotice?.code in setOf(
                            IndexingNotice.CODE_FAILED,
                            IndexingNotice.CODE_PARTIAL,
                        )

                        if (state.indexingNotice != null) {
                            Column(
                                modifier = Modifier.semantics {
                                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                                    stateDescription = statusText
                                }
                            ) {
                                IndexingProgressBar(
                                    progress = state.indexingProgress.coerceAtLeast(0f),
                                    statusText = statusText,
                                    subStatusText = if (showRetry && !state.isRetryingLastFailedIndex) {
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
                                            onClick = actions.onRetryLastFailedIndex,
                                            enabled = !state.isRetryingLastFailedIndex,
                                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                        ) {
                                            Text(stringResource(R.string.shared_btn_retry))
                                        }
                                    }
                                    TextButton(
                                        onClick = actions.onDismissQueueError,
                                        enabled = !state.isRetryingLastFailedIndex,
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
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val newFolderLabel = stringResource(R.string.rag_home_new)
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NexaraColors.SurfaceHigh)
                                    .clickable(
                                        onClickLabel = newFolderLabel,
                                        role = Role.Button,
                                        onClick = { showNewFolderDialog = true },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag(UiTags.RAG_HOME_NEW_FOLDER),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Rounded.CreateNewFolder, null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                                    Text(
                                        stringResource(R.string.rag_home_new),
                                        style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                        color = NexaraColors.Primary
                                    )
                                }
                            }

                            val uploadLabel = stringResource(R.string.rag_home_upload_area)
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NexaraColors.SurfaceHigh)
                                    .clickable(
                                        onClickLabel = uploadLabel,
                                        role = Role.Button,
                                        onClick = actions.onOpenFilePicker,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag(UiTags.RAG_HOME_UPLOAD),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Rounded.CloudUpload, null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                                    Text(
                                        stringResource(R.string.rag_home_upload_area),
                                        style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                        color = NexaraColors.Primary
                                    )
                                }
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

                        if (state.selectedIds.isNotEmpty()) {
                            NexaraGlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .testTag(UiTags.RAG_HOME_SELECTION_BAR),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(R.string.rag_home_selected_count, state.selectedIds.size),
                                            style = NexaraTypography.labelMedium,
                                            color = NexaraColors.OnSurfaceVariant,
                                        )
                                        TextButton(
                                            onClick = { state.selectedIds.clear() },
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
                                            onClick = { showMoveSheet = true },
                                            enabled = !isMovingSelection && !isDeletingSelection,
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag(UiTags.RAG_HOME_MOVE_SELECTION),
                                        ) {
                                            Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text(stringResource(R.string.rag_home_move))
                                        }
                                        TextButton(
                                            onClick = {
                                                actions.onReindexDocuments(state.selectedIds.toList())
                                                state.selectedIds.clear()
                                            },
                                            enabled = !isMovingSelection && !isDeletingSelection,
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag(UiTags.RAG_HOME_REINDEX_SELECTION),
                                        ) {
                                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text(stringResource(R.string.rag_home_reindex))
                                        }
                                        TextButton(
                                            onClick = {
                                                requestDocumentDelete(state.selectedIds.toList()) { result ->
                                                    applyBatchSelectionResult(
                                                        selectedIds = state.selectedIds,
                                                        movingIds = result.attemptedIds.toSet(),
                                                        failedIds = result.failedIds.toSet(),
                                                        succeeded = result.attemptedIds.isNotEmpty() && result.failedIds.isEmpty(),
                                                    )
                                                }
                                            },
                                            enabled = !isMovingSelection && !isDeletingSelection,
                                            colors = ButtonDefaults.textButtonColors(contentColor = NexaraColors.Error),
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

                    PortalTab.MEMORY -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .testTag(UiTags.RAG_HOME_MEMORY_CONTENT),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.rag_home_memory_section),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                            }
                            item {
                                NexaraGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = NexaraShapes.large as RoundedCornerShape
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.rag_home_memory_total_count, state.memoryVectors.size),
                                            style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = NexaraColors.OnSurface
                                        )
                                        Text(
                                            stringResource(R.string.rag_home_memory_est_tokens, state.memoryVectors.sumOf { it.content.length / 3 }),
                                            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                            color = NexaraColors.OnSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (state.memoryVectors.isEmpty()) {
                                item {
                                    NexaraGlassCard(Modifier.fillMaxWidth(), NexaraShapes.large as RoundedCornerShape) {
                                        Column(
                                            Modifier.fillMaxWidth().padding(32.dp),
                                            Arrangement.spacedBy(8.dp),
                                            Alignment.CenterHorizontally,
                                        ) {
                                            Icon(Icons.Rounded.Psychology, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(40.dp))
                                            Text(stringResource(R.string.rag_home_memory_empty), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                        }
                                    }
                                }
                            } else {
                                items(state.memoryVectors, key = { it.id }) { memory ->
                                    val isExpanded = expandedMemoryId == memory.id
                                    val expandLabel = stringResource(
                                        if (isExpanded) R.string.common_cd_collapse else R.string.common_cd_expand
                                    )
                                    val deleteLabel = stringResource(R.string.common_cd_delete)
                                    NexaraGlassCard(
                                        Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                role = Role.Button,
                                                onClickLabel = expandLabel,
                                                onLongClickLabel = deleteLabel,
                                                onClick = {
                                                    expandedMemoryId = if (isExpanded) null else memory.id
                                                },
                                                onLongClick = {
                                                    memoryDeleteTarget = memory
                                                },
                                            ),
                                        RoundedCornerShape(12.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = if (isExpanded) memory.content else memory.content.take(120) + if (memory.content.length > 120) "…" else "",
                                                style = NexaraTypography.bodyMedium,
                                                color = NexaraColors.OnSurface,
                                                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                memory.sessionId?.let {
                                                    Text(
                                                        "${stringResource(R.string.sessions_tag_session)}: ${it.take(8)}…",
                                                        style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                                        color = NexaraColors.Primary.copy(alpha = 0.7f),
                                                    )
                                                }
                                                Text(
                                                    sdf.format(java.util.Date(memory.createdAt)),
                                                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                                                    color = NexaraColors.OnSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
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
                    Text(stringResource(R.string.rag_home_new_folder_title), style = NexaraTypography.headlineSmall)
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
            containerColor = NexaraColors.SurfaceDim,
            titleContentColor = NexaraColors.OnSurface,
            textContentColor = NexaraColors.OnSurfaceVariant,
        )
    }

    if (showMoveSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (!isMovingSelection) showMoveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.testTag(UiTags.RAG_HOME_MOVE_SHEET),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.rag_home_move_to_folder), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                state.folders.forEach { folder ->
                    val moveLabel = stringResource(R.string.rag_home_move)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceContainer)
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
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(folder.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(
                            stringResource(R.string.rag_home_docs_count, state.folderStats[folder.id] ?: 0),
                            style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                            color = NexaraColors.OnSurfaceVariant,
                        )
                    }
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
                    style = NexaraTypography.headlineSmall,
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
                                pendingDocumentDelete = null
                            }
                        }
                    },
                    enabled = pendingDelete.ids.isNotEmpty() && !isDeletingSelection,
                    colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Error),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON),
                ) {
                    Text(stringResource(R.string.shared_btn_delete))
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
            containerColor = NexaraColors.SurfaceDim,
            titleContentColor = NexaraColors.OnSurface,
            textContentColor = NexaraColors.OnSurfaceVariant,
        )
    }

    if (memoryDeleteTarget != null) {
        AlertDialog(
            onDismissRequest = { memoryDeleteTarget = null },
            title = {
                Text(stringResource(R.string.rag_home_memory_delete_confirm_title), style = NexaraTypography.headlineSmall)
            },
            text = {
                Text(stringResource(R.string.rag_home_memory_delete_confirm_msg), style = NexaraTypography.bodyMedium)
            },
            confirmButton = {
                Button(
                    onClick = {
                        memoryDeleteTarget?.let { actions.onDeleteMemory(it.id) }
                        memoryDeleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexaraColors.Error)
                ) {
                    Text(stringResource(R.string.shared_btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDeleteTarget = null }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
            containerColor = NexaraColors.SurfaceDim,
            titleContentColor = NexaraColors.OnSurface,
            textContentColor = NexaraColors.OnSurfaceVariant,
        )
    }
}
