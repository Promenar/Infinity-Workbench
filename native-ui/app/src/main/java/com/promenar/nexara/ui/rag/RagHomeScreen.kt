package com.promenar.nexara.ui.rag

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import com.promenar.nexara.data.repository.CompositeWorkspaceConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

data class RagFolderCrumb(
    val id: String,
    val name: String,
) : java.io.Serializable

@Composable
private fun RagFolderBreadcrumbsBar(
    folderStack: List<RagFolderCrumb>,
    rootTitle: String,
    onNavigateBack: () -> Unit,
    onNavigateToCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = NexaraSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folderStack.isNotEmpty()) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("rag_folder_back_btn"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.rag_back_to_parent),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val allCrumbs = remember(folderStack, rootTitle) {
            listOf(RagFolderCrumb(id = "", name = rootTitle)) + folderStack
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            allCrumbs.forEachIndexed { index, crumb ->
                val isLast = index == allCrumbs.lastIndex
                Text(
                    text = crumb.name,
                    style = if (isLast) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLast) {
                            onNavigateToCrumb(index)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (!isLast) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

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

/**
 * 搜索结果不会呈现原文件列表中的选择项；进入非空搜索时立即清空旧选择，
 * 避免底部批量操作继续作用于当前不可见的文档。
 */
internal fun applyRagSearchSelectionPolicy(
    selectedIds: MutableList<String>,
    query: String,
) {
    if (query.isNotBlank()) selectedIds.clear()
}

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
    val availableWorkspaceRoots by viewModel.availableWorkspaceRoots.collectAsState()
    val selectedWorkspaceSessionId by viewModel.selectedWorkspaceSessionId.collectAsState()
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

    var folderStack by rememberSaveable(workspaceRootUuid) {
        mutableStateOf(listOf<RagFolderCrumb>())
    }
    val activeFolderId = folderStack.lastOrNull()?.id ?: workspaceRootUuid

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val targetFolder = activeFolderId?.takeIf { it != workspaceRootUuid }
            viewModel.importDocuments(uris, folderId = targetFolder)
        }
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
        onCreateFolder = { name -> viewModel.createFolder(name, parentFolderId = activeFolderId) },
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
        activeFolderId,
        folderStack,
        searchQuery,
        searchState,
        indexingFileIds,
        kgExtractionStates,
        availableWorkspaceRoots,
        selectedWorkspaceSessionId,
    ) {
        { modifier, selectedDocumentIds, requestDelete ->
            BackHandler(enabled = folderStack.isNotEmpty() && searchQuery.isBlank()) {
                folderStack = folderStack.dropLast(1)
            }

            Column(modifier = modifier) {
                if (searchQuery.isBlank()) {
                    RagFolderBreadcrumbsBar(
                        folderStack = folderStack,
                        rootTitle = stringResource(R.string.rag_root_all_files),
                        onNavigateBack = { folderStack = folderStack.dropLast(1) },
                        onNavigateToCrumb = { index ->
                            folderStack = if (index == 0) emptyList() else folderStack.take(index)
                        },
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        key(workspaceRootUuid, activeFolderId) {
                            FilesPanel(
                                workspaceRootUuid = workspaceRootUuid,
                                workspaceRepo = viewModel.getWorkspaceRepo(),
                                currentParentUuid = activeFolderId,
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
                                onFolderClick = { folderId, folderName ->
                                    // 兼顾页面下钻与导航契约: onFolderClick = onNavigateToFolder
                                    folderStack = folderStack + RagFolderCrumb(folderId, folderName)
                                },
                                onFileClick = { docId ->
                                    workspaceRootUuid?.let { root ->
                                        actions.onNavigateToDocEditor(root, docId)
                                    }
                                },
                            )
                        }
                    }
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

internal object RecoveryRagUiTags {
    const val SOURCE_SELECTOR = "recovery_rag_source_selector"
    const val SOURCE_OPTION_PREFIX = "recovery_rag_source_option:"

    fun sourceOption(sessionId: String): String = SOURCE_OPTION_PREFIX + sessionId
}

@Composable
internal fun RagWorkspaceSourceSelector(
    sources: List<RagWorkspaceSource>,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
) {
    if (sources.size <= 1) return
    val selected = sources.firstOrNull { it.sessionId == selectedSessionId } ?: sources.first()
    var expanded by remember(selected.sessionId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
    ) {
        Text(
            text = stringResource(R.string.rag_recovery_source_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RecoveryRagUiTags.SOURCE_SELECTOR),
                contentPadding = PaddingValues(horizontal = NexaraSpacing.Medium),
            ) {
                Text(
                    text = selected.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                sources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = source.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(source.sessionId)
                        },
                        modifier = Modifier.testTag(RecoveryRagUiTags.sourceOption(source.sessionId)),
                    )
                }
            }
        }
    }
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
        floatingActionButton = {
            RagStatusFab(state = state, actions = actions)
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
                    .padding(horizontal = NexaraSpacing.ScreenHorizontal)
                    .padding(top = NexaraSpacing.Small),
            ) {
                RagPortalTabSwitcher(
                    selectedTab = state.currentTab,
                    onTabSelected = actions.onChangeTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = NexaraSpacing.Small),
                )

                NexaraSearchBar(
                    value = state.searchQuery,
                    onValueChange = { query ->
                        applyRagSearchSelectionPolicy(state.selectedIds, query)
                        actions.onSearch(query)
                    },
                    placeholder = stringResource(
                        if (state.currentTab == PortalTab.DOCUMENTS) {
                            R.string.rag_home_search
                        } else {
                            R.string.rag_home_memory_search
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = NexaraSpacing.Small)
                        .testTag(UiTags.RAG_HOME_SEARCH),
                )

                when (state.currentTab) {
                    PortalTab.DOCUMENTS -> {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = NexaraSpacing.Small),
                            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        ) {
                            val buttonPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            FilledTonalButton(
                                onClick = actions.onOpenFilePicker,
                                contentPadding = buttonPadding,
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = NexaraSpacing.MinimumTouchTarget,
                                        minHeight = NexaraSpacing.MinimumTouchTarget,
                                    )
                                    .testTag(UiTags.RAG_HOME_UPLOAD),
                            ) {
                                Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(stringResource(R.string.rag_folder_upload))
                            }
                            FilledTonalButton(
                                onClick = { showNewFolderDialog = true },
                                contentPadding = buttonPadding,
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = NexaraSpacing.MinimumTouchTarget,
                                        minHeight = NexaraSpacing.MinimumTouchTarget,
                                    )
                                    .testTag(UiTags.RAG_HOME_NEW_FOLDER),
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(stringResource(R.string.rag_home_new_folder_title))
                            }
                            FilledTonalButton(
                                onClick = actions.onOpenGraph,
                                contentPadding = buttonPadding,
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = NexaraSpacing.MinimumTouchTarget,
                                        minHeight = NexaraSpacing.MinimumTouchTarget,
                                    )
                                    .testTag(UiTags.RAG_HOME_TAB_GRAPH),
                            ) {
                                Icon(Icons.Rounded.Hub, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(stringResource(R.string.rag_details_tab_knowledge_graph))
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
                        val filteredMemories = remember(state.memoryVectors, state.searchQuery) {
                            if (state.searchQuery.isBlank()) {
                                state.memoryVectors
                            } else {
                                state.memoryVectors.filter {
                                    it.content.contains(state.searchQuery, ignoreCase = true)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(UiTags.RAG_HOME_MEMORY_CONTENT),
                            contentPadding = PaddingValues(bottom = NexaraSpacing.XLarge),
                            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        ) {
                            if (state.memoryVectors.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 96.dp)
                                            .testTag("memory-empty"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Psychology,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(32.dp),
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_empty_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_empty),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else if (filteredMemories.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 96.dp)
                                            .testTag("memory-search-empty"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Search,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(32.dp),
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_search_empty_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.rag_home_memory_search_empty),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(filteredMemories, key = { it.id }) { memory ->
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
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = NexaraSpacing.Medium),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.rag_home_memory_footer_count, filteredMemories.size),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
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

@Composable
internal fun RagPortalTabSwitcher(
    selectedTab: PortalTab,
    onTabSelected: (PortalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val tabs = remember { listOf(PortalTab.DOCUMENTS, PortalTab.MEMORY) }
    val selectedIndex = if (selectedTab == PortalTab.MEMORY) 1 else 0

    BoxWithConstraints(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (selectedIndex == 0) 0.dp else tabWidth,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "portalTabIndicatorOffset",
        )

        // 胶囊滑动高亮底座 (与 Homebar 选中态风格一致)
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )

        // 选项文本与点击行
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 200),
                    label = "portalTabContentColor_$index",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (selectedTab != tab) {
                                    val hapticConstant = if (Build.VERSION.SDK_INT >= 34) {
                                        HapticFeedbackConstants.SEGMENT_TICK
                                    } else {
                                        HapticFeedbackConstants.CLOCK_TICK
                                    }
                                    view.performHapticFeedback(hapticConstant)
                                    onTabSelected(tab)
                                }
                            },
                        )
                        .then(
                            when (tab) {
                                PortalTab.DOCUMENTS -> Modifier.testTag(UiTags.RAG_HOME_TAB_DOCUMENTS)
                                PortalTab.MEMORY -> Modifier.testTag(UiTags.RAG_HOME_TAB_MEMORY)
                                PortalTab.GRAPH -> Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (tab == PortalTab.DOCUMENTS) {
                                Icons.Rounded.Description
                            } else {
                                Icons.Rounded.Psychology
                            },
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                if (tab == PortalTab.DOCUMENTS) {
                                    R.string.rag_home_documents
                                } else {
                                    R.string.rag_home_memory
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RagStatusFab(
    state: RagHomeScreenState,
    actions: RagHomeScreenActions,
    modifier: Modifier = Modifier,
) {
    val isVectorizing = state.isIndexing || state.indexingFileIds.isNotEmpty()
    val isExtractingKg = remember(state.kgExtractionStates) {
        state.kgExtractionStates.values.any { it == KgStatus.IN_PROGRESS }
    }
    val hasKgFailed = remember(state.kgExtractionStates) {
        state.kgExtractionStates.values.any { it == KgStatus.FAILED }
    }
    val isRunning = isVectorizing || isExtractingKg
    val isBothRunning = isVectorizing && isExtractingKg

    val isError = state.indexingNotice?.severity == NoticeSeverity.Error ||
        state.canRetryLastFailedIndex ||
        state.canRetryPendingRenameIndex ||
        hasKgFailed

    var wasRunning by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, isError) {
        if (isRunning) {
            wasRunning = true
            showSuccess = false
        } else if (wasRunning && !isError) {
            wasRunning = false
            showSuccess = true
            delay(3500)
            showSuccess = false
        } else {
            wasRunning = false
        }
    }

    val isVisible = isRunning || isError || showSuccess

    var iconToggle by remember { mutableStateOf(false) }
    LaunchedEffect(isBothRunning) {
        if (isBothRunning) {
            while (isActive) {
                delay(1800)
                iconToggle = !iconToggle
            }
        } else {
            iconToggle = false
        }
    }

    var expanded by remember { mutableStateOf(false) }

    val resolvedNotice = state.indexingNotice?.let { IndexingNotice.template(it) }
    val noticeText = resolvedNotice?.let { resolved ->
        stringResource(resolved.resourceId, *resolved.args.toTypedArray())
    } ?: state.indexingNotice?.technical

    val titleText = when {
        isError -> stringResource(R.string.rag_status_failed)
        showSuccess -> stringResource(R.string.rag_status_completed)
        isBothRunning -> stringResource(R.string.rag_status_tasks_running)
        isExtractingKg -> stringResource(R.string.rag_status_kg_extracting)
        isVectorizing -> stringResource(R.string.rag_status_vectorizing)
        else -> stringResource(R.string.rag_status_ready)
    }

    val indexAction = resolveRagHomeIndexAction(
        noticeCode = state.indexingNotice?.code,
        canRetryLastFailedIndex = state.canRetryLastFailedIndex,
        canRetryPendingIndex = state.canRetryPendingRenameIndex,
    )
    val showRetry = indexAction != RagHomeIndexAction.None
    val retrying = state.isRetryingLastFailedIndex || state.isRetryingPendingRenameIndex

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(250)) + scaleIn(tween(250)),
        exit = fadeOut(tween(200)) + scaleOut(tween(200)),
        modifier = modifier.testTag(UiTags.RAG_HOME_INDEXING_NOTICE),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.padding(bottom = 8.dp, end = 8.dp),
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(250)) + expandHorizontally(tween(300), expandFrom = Alignment.End),
                exit = fadeOut(tween(200)) + shrinkHorizontally(tween(250), shrinkTowards = Alignment.End),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .widthIn(min = 220.dp, max = 290.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { expanded = false },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.common_dismiss),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (isVectorizing && state.indexingProgress >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.indexingProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            )
                        }

                        if (!noticeText.isNullOrBlank()) {
                            Text(
                                text = noticeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isError) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        expanded = false
                                        actions.onOpenConfig()
                                    },
                                    modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 36.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.rag_status_go_to_settings),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (showRetry) {
                                    TextButton(
                                        onClick = {
                                            when (indexAction) {
                                                RagHomeIndexAction.RetryFailed -> actions.onRetryLastFailedIndex()
                                                RagHomeIndexAction.RetryPending -> actions.onRetryPendingRenameIndex()
                                                RagHomeIndexAction.None -> {}
                                            }
                                        },
                                        enabled = !retrying,
                                        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 36.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.shared_btn_retry),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        actions.onDismissQueueError()
                                        expanded = false
                                    },
                                    enabled = !retrying,
                                    modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 36.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.common_dismiss),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val containerColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.errorContainer
                    showSuccess -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                label = "FabContainerColor",
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    showSuccess -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                },
                label = "FabContentColor",
            )

            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                }

                Surface(
                    onClick = { expanded = !expanded },
                    shape = CircleShape,
                    color = containerColor,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = when {
                                isError -> 0
                                showSuccess -> 1
                                isBothRunning && iconToggle -> 2
                                isBothRunning && !iconToggle -> 3
                                isExtractingKg -> 2
                                else -> 3
                            },
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                            label = "FabIcon",
                        ) { target ->
                            when (target) {
                                0 -> Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.rag_status_failed),
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                1 -> Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.rag_status_completed),
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                2 -> Icon(
                                    imageVector = Icons.Rounded.Hub,
                                    contentDescription = stringResource(R.string.rag_status_kg_extracting),
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                else -> Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = stringResource(R.string.rag_status_vectorizing),
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

