package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.KgStatusIcon
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf

internal enum class FileNodeActivation {
    ToggleSelection,
    ToggleExpansion,
    NavigateFolder,
    OpenFile,
}

internal fun resolveFileNodeActivation(
    isMultiSelectMode: Boolean,
    isDirectory: Boolean,
    hasFolderClick: Boolean,
    hasFileClick: Boolean,
): FileNodeActivation? = when {
    isMultiSelectMode -> FileNodeActivation.ToggleSelection
    isDirectory && hasFolderClick -> FileNodeActivation.NavigateFolder
    isDirectory -> FileNodeActivation.ToggleExpansion
    hasFileClick -> FileNodeActivation.OpenFile
    else -> null
}

internal fun supportsFileMultiSelect(
    hasReindex: Boolean,
    hasDelete: Boolean,
): Boolean = hasReindex || hasDelete

internal fun hasFileNodeMenuActions(
    isDirectory: Boolean,
    hasReindex: Boolean,
    hasDelete: Boolean,
    hasRename: Boolean,
    hasMove: Boolean,
    hasExtractKG: Boolean,
    hasViewKG: Boolean,
    hasCopy: Boolean,
): Boolean = hasDelete || hasRename || hasViewKG ||
    (!isDirectory && (hasReindex || hasMove || hasExtractKG || hasCopy))

@Composable
internal fun FilesPanel(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = "",
    useScroll: Boolean = true,
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((Collection<String>, (FileBatchOperationResult) -> Unit) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    onViewKG: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    folders: List<FileEntry> = emptyList(),
    rootFiles: List<FileEntry>? = null,
    initiallyExpandedIds: Set<String> = emptySet(),
    initialChildrenByParent: Map<String, List<FileEntry>> = emptyMap(),
    isLoading: Boolean = false,
    hasLoadError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
    externalSelectedIds: MutableList<String>? = null,
    showSelectionOverlay: Boolean = true,
    onFolderClick: ((String, String) -> Unit)? = null,
    onFileClick: ((String) -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val rootsFlow = remember(rootFiles, workspaceRootUuid, workspaceRepo) {
        if (rootFiles == null) workspaceRootUuid?.let { workspaceRepo.observeChildren(it, it) }
            ?: flowOf(emptyList())
        else flowOf(rootFiles)
    }
    val roots by rootsFlow.collectAsState(initial = rootFiles.orEmpty())

    val searchVisibleIds by produceState<Set<String>?>(
        initialValue = null,
        workspaceRootUuid,
        workspaceRepo,
        searchQuery,
    ) {
        val rootUuid = workspaceRootUuid
        if (rootUuid == null || searchQuery.isBlank()) {
            value = null
        } else {
            workspaceRepo.searchByName(rootUuid, searchQuery).collectLatest { matches ->
                value = resolveSearchVisibleIds(matches, rootUuid) { uuid ->
                    workspaceRepo.getByUuid(rootUuid, uuid)
                }
            }
        }
    }
    val filteredRoots = if (searchQuery.isBlank()) roots else {
        val visibleIds = searchVisibleIds.orEmpty()
        roots.filter { it.uuid in visibleIds }
    }
    val panelState = resolveFilesPanelUiState(
        workspaceReady = workspaceRootUuid != null,
        isLoading = isLoading,
        hasLoadError = hasLoadError,
        itemCount = roots.size,
        filteredItemCount = filteredRoots.size,
        searchQuery = searchQuery,
    )

    val childrenByParent = remember(workspaceRootUuid, workspaceRepo, initialChildrenByParent) {
        mutableStateMapOf<String, List<FileEntry>>().apply { putAll(initialChildrenByParent) }
    }
    var explicitlyExpandedIds by rememberSaveable(workspaceRootUuid, initiallyExpandedIds) {
        mutableStateOf(ArrayList(initiallyExpandedIds))
    }
    var explicitlyCollapsedIds by rememberSaveable(workspaceRootUuid) {
        mutableStateOf(arrayListOf<String>())
    }
    val expansionOverrides = remember(explicitlyExpandedIds, explicitlyCollapsedIds) {
        buildMap {
            explicitlyExpandedIds.forEach { put(it, true) }
            explicitlyCollapsedIds.forEach { put(it, false) }
        }
    }
    val projectedChildren = if (searchQuery.isBlank()) {
        childrenByParent
    } else {
        val visibleIds = searchVisibleIds.orEmpty()
        childrenByParent.mapValues { (_, children) -> children.filter { it.uuid in visibleIds } }
    }
    val forceExpandedIds = if (searchQuery.isBlank()) emptySet() else searchVisibleIds.orEmpty()
    val visibleNodes = projectVisibleFileNodes(
        roots = filteredRoots,
        childrenByParent = projectedChildren,
        expansionOverrides = expansionOverrides,
        forceExpandedIds = forceExpandedIds,
    )
    val composedNodeRefCounts = remember(workspaceRootUuid, workspaceRepo) {
        mutableStateMapOf<String, Int>()
    }
    val expandedDirectoryIds = visibleNodes.asSequence()
        .filter { node ->
            node.file.isDirectory && isFileNodeExpanded(
                uuid = node.file.uuid,
                depth = node.depth,
                expansionOverrides = expansionOverrides,
                forceExpandedIds = forceExpandedIds,
            )
        }
        .mapTo(linkedSetOf()) { it.file.uuid }
    val requiredDirectorySubscriptionIds = resolveRequiredDirectorySubscriptionIds(
        visibleNodes = visibleNodes,
        visibleNodeIds = composedNodeRefCounts.keys.toSet(),
        expandedDirectoryIds = expandedDirectoryIds,
    )
    requiredDirectorySubscriptionIds.forEach { parentUuid ->
        key(parentUuid) {
            LaunchedEffect(workspaceRootUuid, workspaceRepo, parentUuid) {
                val rootUuid = workspaceRootUuid ?: return@LaunchedEffect
                workspaceRepo.observeChildren(rootUuid, parentUuid).collectLatest { children ->
                    childrenByParent[parentUuid] = children
                }
            }
        }
    }

    // 多选状态
    val localSelectedIds = remember { mutableStateListOf<String>() }
    val selectedIds = externalSelectedIds ?: localSelectedIds
    val supportsMultiSelect = supportsFileMultiSelect(
        hasReindex = onReindex != null,
        hasDelete = onDelete != null,
    )
    val isMultiSelectMode = selectedIds.isNotEmpty() && supportsMultiSelect
    var deleteFailure by remember { mutableStateOf<FileBatchOperationResult?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    val requestDelete: (Collection<String>) -> Unit = request@{ ids ->
        val deleteAction = onDelete ?: return@request
        val attemptedIds = ids.distinct()
        if (attemptedIds.isEmpty() || isDeleting) return@request
        isDeleting = true
        deleteAction(attemptedIds) { result ->
            reduceSelectionAfterFileOperation(selectedIds, result)
            deleteFailure = result.takeIf { it.failedIds.isNotEmpty() }
            isDeleting = false
        }
    }

    val toggleExpanded: (VisibleFileNode) -> Unit = { node ->
        val currentlyExpanded = isFileNodeExpanded(
            uuid = node.file.uuid,
            depth = node.depth,
            expansionOverrides = expansionOverrides,
            forceExpandedIds = forceExpandedIds,
        )
        val id = node.file.uuid
        if (currentlyExpanded) {
            explicitlyExpandedIds = ArrayList(explicitlyExpandedIds).apply { remove(id) }
            explicitlyCollapsedIds = ArrayList(explicitlyCollapsedIds).apply { if (id !in this) add(id) }
        } else {
            explicitlyCollapsedIds = ArrayList(explicitlyCollapsedIds).apply { remove(id) }
            explicitlyExpandedIds = ArrayList(explicitlyExpandedIds).apply { if (id !in this) add(id) }
        }
    }

    val content = @Composable { node: VisibleFileNode ->
        val expanded = isFileNodeExpanded(
            uuid = node.file.uuid,
            depth = node.depth,
            expansionOverrides = expansionOverrides,
            forceExpandedIds = forceExpandedIds,
        )
        DisposableEffect(node.file.uuid, workspaceRootUuid, workspaceRepo) {
            composedNodeRefCounts[node.file.uuid] = (composedNodeRefCounts[node.file.uuid] ?: 0) + 1
            onDispose {
                val remaining = (composedNodeRefCounts[node.file.uuid] ?: 1) - 1
                if (remaining > 0) {
                    composedNodeRefCounts[node.file.uuid] = remaining
                } else {
                    composedNodeRefCounts.remove(node.file.uuid)
                }
            }
        }
        FileTreeRow(
            node = node,
            expanded = expanded,
            onToggleExpanded = { toggleExpanded(node) },
            workspaceRootUuid = workspaceRootUuid!!,
            workspaceRepo = workspaceRepo,
            onReindex = onReindex,
            onDelete = onDelete?.let { { id -> requestDelete(listOf(id)) } },
            onRename = onRename,
            onMove = onMove,
            onExtractKG = onExtractKG,
            onViewKG = onViewKG,
            onCopy = onCopy,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            selectedIds = selectedIds,
            isMultiSelectMode = isMultiSelectMode,
            onFolderClick = onFolderClick,
            onFileClick = onFileClick,
            folders = folders,
            nowMillis = nowMillis,
        )
    }

    Box(modifier = if (useScroll) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        Column(
            modifier = (if (useScroll) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(bottom = if (showSelectionOverlay && isMultiSelectMode) 88.dp else 0.dp),
        ) {
            val bodyModifier = if (useScroll) Modifier.weight(1f) else Modifier.fillMaxWidth()
            deleteFailure?.let { failure ->
                val failedLabel = stringResource(R.string.common_cd_failed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = failedLabel
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(failedLabel, style = NexaraTypography.labelMedium, color = NexaraColors.Error)
                    TextButton(
                        onClick = { requestDelete(failure.failedIds) },
                        enabled = !isDeleting,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text(stringResource(R.string.shared_btn_retry)) }
                    TextButton(
                        onClick = { deleteFailure = null },
                        enabled = !isDeleting,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text(stringResource(R.string.common_dismiss)) }
                }
            }
            when (panelState) {
                FilesPanelUiState.Loading -> FilesPanelLoadingState(bodyModifier)
                FilesPanelUiState.Error -> FilesPanelErrorState(
                    modifier = bodyModifier,
                    onRetry = onRetryLoad,
                )
                FilesPanelUiState.Empty -> EmptyFilesState(bodyModifier)
                FilesPanelUiState.SearchEmpty -> SearchEmptyFilesState(bodyModifier)
                FilesPanelUiState.Content -> Surface(
                    modifier = bodyModifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    if (useScroll) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("files_panel_tree_list"),
                        ) {
                            items(
                                items = visibleNodes,
                                key = { it.file.uuid },
                                contentType = { if (it.file.isDirectory) "directory" else "file" },
                            ) { node -> content(node) }
                        }
                    } else {
                        // 外层页面拥有滚动时保持非滚动容器，但仍以 UUID key 渲染单层投影。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("files_panel_tree_list"),
                        ) {
                            visibleNodes.forEach { node -> key(node.file.uuid) { content(node) } }
                        }
                    }
                }
            }
        }

        // 底部批量操作栏：统一在真实屏幕底部居中，正文已预留空间。
        FilesPanelSelectionOverlay(
            visible = showSelectionOverlay && isMultiSelectMode,
            selectedCount = selectedIds.size,
            onClear = { selectedIds.clear() },
            onReindexAll = onReindex?.let { reindexFn ->
                { selectedIds.forEach { id -> reindexFn(id) }; selectedIds.clear() }
            },
            onDeleteAll = if (onDelete != null) {
                { requestDelete(selectedIds.toList()) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun BoxScope.FilesPanelSelectionOverlay(
    selectedCount: Int,
    onClear: () -> Unit,
    onReindexAll: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .testTag("files_panel_selection_bar"),
        enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(300)) +
            fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
        exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(200)) +
            fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
    ) {
        BatchActionBar(
            selectedCount = selectedCount,
            onClear = onClear,
            onReindexAll = onReindexAll,
            onDeleteAll = onDeleteAll,
        )
    }
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onReindexAll: (() -> Unit)?,
    onDeleteAll: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NexaraColors.SurfaceLow.copy(alpha = 0.95f))
            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.files_selected_count, selectedCount),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurface,
                )
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.files_cancel),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.Primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onReindexAll != null) {
                    TextButton(
                        onClick = onReindexAll,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.files_reindex),
                            style = NexaraTypography.labelSmall,
                            color = NexaraColors.Primary,
                        )
                    }
                }
                if (onDeleteAll != null) {
                    TextButton(
                        onClick = onDeleteAll,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.shared_btn_delete),
                            style = NexaraTypography.labelSmall,
                            color = NexaraColors.Error,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: VisibleFileNode,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    workspaceRootUuid: String,
    workspaceRepo: IWorkspaceRepository,
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    onViewKG: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    selectedIds: MutableList<String> = mutableStateListOf(),
    isMultiSelectMode: Boolean = false,
    onFolderClick: ((String, String) -> Unit)? = null,
    onFileClick: ((String) -> Unit)? = null,
    folders: List<FileEntry> = emptyList(),
    nowMillis: Long,
) {
    val file = node.file
    var showMenu by rememberSaveable(file.uuid, "menu") { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(file.uuid, "rename") { mutableStateOf(false) }
    var showMoveSheet by rememberSaveable(file.uuid, "move") { mutableStateOf(false) }
    val isSelected = file.uuid in selectedIds
    val optionsLabel = stringResource(R.string.chat_cd_options)
    val supportsMultiSelect = supportsFileMultiSelect(
        hasReindex = onReindex != null,
        hasDelete = onDelete != null,
    )
    val hasMenuActions = supportsMultiSelect || hasFileNodeMenuActions(
        isDirectory = file.isDirectory,
        hasReindex = onReindex != null,
        hasDelete = onDelete != null,
        hasRename = onRename != null,
        hasMove = onMove != null,
        hasExtractKG = onExtractKG != null,
        hasViewKG = onViewKG != null,
        hasCopy = onCopy != null,
    )
    val activation = resolveFileNodeActivation(
        isMultiSelectMode = isMultiSelectMode,
        isDirectory = file.isDirectory,
        hasFolderClick = onFolderClick != null,
        hasFileClick = onFileClick != null,
    )
    val handleActivate: (() -> Unit)? = activation?.let { resolvedActivation ->
        {
            when (resolvedActivation) {
                FileNodeActivation.ToggleSelection -> {
                    if (isSelected) selectedIds.remove(file.uuid) else selectedIds.add(file.uuid)
                }
                FileNodeActivation.ToggleExpansion -> onToggleExpanded()
                FileNodeActivation.NavigateFolder -> onFolderClick?.invoke(file.uuid, file.name)
                FileNodeActivation.OpenFile -> onFileClick?.invoke(file.uuid)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        FileRow(
            file = file,
            depth = node.depth,
            expanded = expanded,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            isMultiSelectMode = isMultiSelectMode,
            isSelected = isSelected,
            onSelectionChange = { checked ->
                if (checked && file.uuid !in selectedIds) selectedIds.add(file.uuid)
                if (!checked) selectedIds.remove(file.uuid)
            },
            onOpenMenu = if (hasMenuActions) ({ showMenu = true }) else null,
            modifier = Modifier
                .testTag("files_panel_node_${file.uuid}")
                .then(
                    if (handleActivate != null) {
                        Modifier.combinedClickable(
                            role = Role.Button,
                            onClickLabel = file.name,
                            onLongClickLabel = optionsLabel.takeIf { hasMenuActions },
                            onLongClick = if (hasMenuActions) ({ showMenu = true }) else null,
                            onClick = handleActivate,
                        )
                    } else {
                        Modifier
                    },
                ),
            nowMillis = nowMillis,
        )

        DropdownMenu(expanded = showMenu && hasMenuActions, onDismissRequest = { showMenu = false }) {
            if (!file.isDirectory) {
                if (onReindex != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_reindex)) },
                        onClick = { showMenu = false; onReindex(file.uuid) },
                        modifier = Modifier.testTag(UiTags.fileNodeReindex(file.uuid)),
                    )
                }
                if (onExtractKG != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_extract_knowledge_graph)) },
                        onClick = { showMenu = false; onExtractKG(file.uuid) },
                        modifier = Modifier.testTag("files_panel_extract_kg_action_${file.uuid}"),
                    )
                }
                if (onViewKG != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_view_graph)) },
                        onClick = { showMenu = false; onViewKG(file.uuid) },
                        modifier = Modifier.testTag("files_panel_view_kg_action_${file.uuid}"),
                    )
                }
            } else if (onViewKG != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_view_folder_graph)) },
                    onClick = { showMenu = false; onViewKG(file.uuid) },
                    modifier = Modifier.testTag("files_panel_view_kg_action_${file.uuid}"),
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_rename)) },
                    onClick = { showMenu = false; showRenameDialog = true },
                    modifier = Modifier.testTag("files_panel_rename_action_${file.uuid}"),
                )
            }
            if (onMove != null && !file.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_move_to)) },
                    onClick = { showMenu = false; showMoveSheet = true },
                    modifier = Modifier.testTag("files_panel_move_action_${file.uuid}"),
                )
            }
            if (onCopy != null && !file.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_btn_copy)) },
                    onClick = { showMenu = false; onCopy(file.uuid) },
                    modifier = Modifier.testTag("files_panel_copy_action_${file.uuid}"),
                )
            }
            if (!isMultiSelectMode && supportsMultiSelect) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_multi_select)) },
                    onClick = {
                        showMenu = false
                        if (file.uuid !in selectedIds) selectedIds.add(file.uuid)
                    },
                    modifier = Modifier.testTag(UiTags.fileNodeMultiSelect(file.uuid)),
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_btn_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; onDelete(file.uuid) },
                    modifier = Modifier.testTag("files_panel_delete_action_${file.uuid}"),
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            fileUuid = file.uuid,
            currentName = file.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename?.invoke(file.uuid, newName)
            },
        )
    }

    if (showMoveSheet) {
        MoveToSheet(
            folders = folders,
            workspaceRepo = workspaceRepo,
            workspaceRootUuid = workspaceRootUuid,
            onDismiss = { showMoveSheet = false },
            onSelect = { targetUuid ->
                showMoveSheet = false
                onMove?.invoke(file.uuid, targetUuid)
            },
        )
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    depth: Int,
    expanded: Boolean,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectionChange: (Boolean) -> Unit,
    onOpenMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
    nowMillis: Long,
) {
    val optionsLabel = stringResource(R.string.chat_cd_options)
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (boundedFileTreeIndentLevel(depth) * 12).dp),
    ) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = containerColor),
            headlineContent = {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = if (file.isDirectory) {
                null
            } else {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = formatFileMetadata(file, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IndexStatusBadge(status = resolveIndexStatus(file, indexingFileIds))
                            KgStatusIcon(status = resolveKgStatus(file, kgExtractionStates))
                        }
                    }
                }
            },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMultiSelectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = onSelectionChange,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        )
                    }
                    Icon(
                        imageVector = if (file.isDirectory && expanded) Icons.Rounded.FolderOpen else fileIcon(file),
                        contentDescription = null,
                        tint = if (file.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            trailingContent = onOpenMenu?.let { openMenu ->
                {
                    IconButton(
                        onClick = openMenu,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(UiTags.fileNodeOptions(file.uuid)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = optionsLabel,
                        )
                    }
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun RenameDialog(
    fileUuid: String,
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_rename), style = NexaraTypography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.files_new_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("files_panel_rename_input_$fileUuid"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("files_panel_rename_confirm_$fileUuid"),
            ) {
                Text(stringResource(R.string.files_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_cancel)) }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoveToSheet(
    folders: List<FileEntry>,
    workspaceRepo: IWorkspaceRepository,
    workspaceRootUuid: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val dirs by workspaceRepo.observeChildren(workspaceRootUuid, workspaceRootUuid)
        .collectAsState(initial = emptyList())
    val directoryList = folders.ifEmpty { dirs.filter { it.isDirectory } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.files_move_to), style = MaterialTheme.typography.headlineSmall)
            if (directoryList.isEmpty()) {
                Text(
                    stringResource(R.string.files_no_available_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .testTag("files_panel_move_list"),
            ) {
                item(key = workspaceRootUuid) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.files_root_directory)) },
                        leadingContent = {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(workspaceRootUuid) }
                            .testTag("files_panel_move_destination_$workspaceRootUuid"),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
                items(directoryList, key = { it.uuid }) { dir ->
                    ListItem(
                        headlineContent = {
                            Text(dir.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(dir.uuid) }
                            .testTag("files_panel_move_destination_${dir.uuid}"),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesPanelLoadingState(modifier: Modifier = Modifier) {
    val loadingLabel = stringResource(R.string.shared_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = loadingLabel
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            loadingLabel,
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant,
        )
    }
}

@Composable
private fun FilesPanelErrorState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val errorLabel = stringResource(R.string.resource_explorer_error_workspace_load_failed)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                stateDescription = errorLabel
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            errorLabel,
            style = NexaraTypography.labelMedium,
            color = NexaraColors.Error,
        )
        onRetry?.let { retry ->
            TextButton(
                onClick = retry,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.shared_btn_retry))
            }
        }
    }
}

@Composable
private fun SearchEmptyFilesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.rag_home_search_results), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
        Text(stringResource(R.string.files_empty_title), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
    }
}

@Composable
private fun EmptyFilesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.files_empty_title), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
        Text(
            stringResource(R.string.files_empty_subtitle),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant,
        )
    }
}

private fun fileIcon(file: FileEntry): ImageVector {
    if (file.isDirectory) return Icons.Rounded.Folder
    return when (file.mimeType?.substringBefore("/")?.lowercase()) {
        "image" -> Icons.Rounded.Image
        "audio" -> Icons.Rounded.AudioFile
        "video" -> Icons.Rounded.Movie
        "text", "application" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun resolveIndexStatus(file: FileEntry, indexingFileIds: Set<String> = emptySet()): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.uuid in indexingFileIds) return FileIndexStatus.INDEXING
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    if (file.updatedAt > (file.vectorizedAt ?: 0)) return FileIndexStatus.STALE
    return FileIndexStatus.INDEXED
}

private fun resolveKgStatus(file: FileEntry, kgExtractionStates: Map<String, KgStatus>): KgStatus {
    val state = kgExtractionStates[file.uuid]
    if (state != null) return state
    if (file.kgExtractedAt != null) return KgStatus.COMPLETED
    return KgStatus.NOT_STARTED
}

@Composable
private fun formatFileMetadata(file: FileEntry, nowMillis: Long): String {
    val size = formatFileSize(file.sizeBytes)
    val time = formatRelativeTime(file.updatedAt, nowMillis)
    return "$size · $time"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.0f".format(value)} ${units[digitGroups]}"
}

@Composable
private fun formatRelativeTime(timestamp: Long, nowMillis: Long): String {
    val diff = nowMillis - timestamp
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("MMM d", locale).format(Date(timestamp))
    }
}
