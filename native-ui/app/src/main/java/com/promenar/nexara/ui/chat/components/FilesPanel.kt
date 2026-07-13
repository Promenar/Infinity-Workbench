package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.KgStatusIcon
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.flowOf

@Composable
fun FilesPanel(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = "",
    useScroll: Boolean = true,
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    onViewKG: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    folders: List<FileEntry> = emptyList(),
    rootFiles: List<FileEntry>? = null,
    externalSelectedIds: MutableList<String>? = null,
    onFileClick: (String) -> Unit = {}
) {
    val rootsFlow = if (rootFiles == null) workspaceRootUuid?.let { workspaceRepo.observeChildren(it, it) }
        ?: flowOf(emptyList())
    else flowOf(rootFiles)
    val roots by rootsFlow.collectAsState(initial = rootFiles.orEmpty())

    val filteredRoots = if (searchQuery.isBlank()) roots else {
        roots.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    // 多选状态
    val localSelectedIds = remember { mutableStateListOf<String>() }
    val selectedIds = externalSelectedIds ?: localSelectedIds
    val isMultiSelectMode = selectedIds.isNotEmpty()

    val content = @Composable { root: FileEntry ->
        FileTreeNode(
            file = root, depth = 0, workspaceRootUuid = workspaceRootUuid!!, workspaceRepo = workspaceRepo,
            searchQuery = searchQuery,
            onReindex = onReindex, onDelete = { id ->
                onDelete?.invoke(id); selectedIds.remove(id)
            },
            onRename = onRename, onMove = onMove, onExtractKG = onExtractKG, onViewKG = onViewKG, onCopy = onCopy,
            indexingFileIds = indexingFileIds,
            kgExtractionStates = kgExtractionStates,
            selectedIds = selectedIds, isMultiSelectMode = isMultiSelectMode,
            onFileClick = onFileClick
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (useScroll) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredRoots, key = { it.uuid }) { root -> content(root) }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredRoots.isEmpty() && searchQuery.isBlank()) {
                    EmptyFilesState()
                } else {
                    filteredRoots.forEach { root -> content(root) }
                }
            }
        }

        // 底部批量操作栏（MD3 过渡动画）
        AnimatedVisibility(
            visible = isMultiSelectMode,
            enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(300)) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
            exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(200)) + fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
        ) {
            BatchActionBar(
                selectedCount = selectedIds.size,
                onClear = { selectedIds.clear() },
                onReindexAll = onReindex?.let { reindexFn ->
                    { selectedIds.forEach { id -> reindexFn(id) }; selectedIds.clear() }
                },
                onDeleteAll = onDelete?.let { deleteFn ->
                    { selectedIds.forEach { id -> deleteFn(id) }; selectedIds.clear() }
                }
            )
        }
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
                TextButton(onClick = onClear) {
                    Text(
                        stringResource(R.string.files_cancel),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.Primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onReindexAll != null) {
                    TextButton(onClick = onReindexAll) {
                        Text(
                            stringResource(R.string.files_reindex),
                            style = NexaraTypography.labelSmall,
                            color = NexaraColors.Primary,
                        )
                    }
                }
                if (onDeleteAll != null) {
                    TextButton(onClick = onDeleteAll) {
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

@Composable
private fun FileTreeNode(
    file: FileEntry,
    depth: Int,
    workspaceRootUuid: String,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = "",
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
    onFileClick: (String) -> Unit = {}
) {
    var expanded by rememberSaveable { mutableStateOf(depth < 2) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveSheet by rememberSaveable { mutableStateOf(false) }
    val isSelected = file.uuid in selectedIds

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
        ) {
        // 多选高亮动画
        val bgColor by animateColorAsState(
            targetValue = if (isSelected) NexaraColors.Primary.copy(alpha = 0.08f) else NexaraColors.GlassSurface,
            animationSpec = androidx.compose.animation.core.tween(200),
            label = "selectBg"
        )

        NexaraGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = androidx.compose.animation.core.tween(200))
                .pointerInput(file.uuid, isMultiSelectMode, isSelected) {
                    detectTapGestures(
                        onLongPress = {
                            // 长按仅弹出菜单，不自动进入多选
                            showMenu = true
                        },
                        onTap = {
                            if (isMultiSelectMode) {
                                if (isSelected) selectedIds.remove(file.uuid) else selectedIds.add(file.uuid)
                            } else if (file.isDirectory) {
                                expanded = !expanded
                            } else {
                                onFileClick(file.uuid)
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(12.dp)
        ) {
            FileRow(
                file = file,
                indexingFileIds = indexingFileIds,
                kgExtractionStates = kgExtractionStates,
                isMultiSelectMode = isMultiSelectMode,
                isSelected = isSelected
            )
        }

        // 长按上下文菜单
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (!file.isDirectory) {
                if (onReindex != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_reindex), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                        onClick = { showMenu = false; onReindex(file.uuid) }
                    )
                }
                if (onExtractKG != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_extract_knowledge_graph), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                        onClick = { showMenu = false; onExtractKG(file.uuid) }
                    )
                }
                if (onViewKG != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_view_graph), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                        onClick = { showMenu = false; onViewKG(file.uuid) }
                    )
                }
            } else {
                // 目录: 查看图谱
                if (onViewKG != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_view_folder_graph), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                        onClick = { showMenu = false; onViewKG(file.uuid) }
                    )
                }
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_rename), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                    onClick = { showMenu = false; showRenameDialog = true }
                )
            }
            if (onMove != null && !file.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_move_to), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                    onClick = { showMenu = false; showMoveSheet = true }
                )
            }
            if (onCopy != null && !file.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_btn_copy), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                    onClick = { showMenu = false; onCopy(file.uuid) }
                )
            }
            if (!isMultiSelectMode) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_multi_select), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) },
                    onClick = { showMenu = false; selectedIds.add(file.uuid) }
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.Error) },
                    onClick = { showMenu = false; onDelete(file.uuid) }
                )
            }
        }
    }

    // 重命名对话框
    if (showRenameDialog) {
        RenameDialog(
            currentName = file.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename?.invoke(file.uuid, newName)
            }
        )
    }

    // "移动到"目录选择器
    if (showMoveSheet) {
        MoveToSheet(
            folders = emptyList(), // 目录选择器仅查询当前工作区根目录的直接子项
            workspaceRepo = workspaceRepo,
            workspaceRootUuid = workspaceRootUuid,
            onDismiss = { showMoveSheet = false },
            onSelect = { targetUuid ->
                showMoveSheet = false
                onMove?.invoke(file.uuid, targetUuid)
            }
        )
    }

    if (file.isDirectory) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(200)) + fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
            exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(150)) + fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
        ) {
            val children by workspaceRepo.observeChildren(workspaceRootUuid, file.uuid)
                .collectAsState(initial = emptyList())

            val filteredChildren = if (searchQuery.isBlank()) children else {
                children.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredChildren.forEach { child ->
                    FileTreeNode(
                        file = child, depth = depth + 1, workspaceRootUuid = workspaceRootUuid, workspaceRepo = workspaceRepo,
                        searchQuery = searchQuery, onReindex = onReindex, onDelete = onDelete,
                        onRename = onRename, onMove = onMove, onExtractKG = onExtractKG, onViewKG = onViewKG, onCopy = onCopy,
                        indexingFileIds = indexingFileIds,
                        kgExtractionStates = kgExtractionStates,
                        selectedIds = selectedIds, isMultiSelectMode = isMultiSelectMode,
                        onFileClick = onFileClick
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    indexingFileIds: Set<String> = emptySet(),
    kgExtractionStates: Map<String, KgStatus> = emptyMap(),
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.background(NexaraColors.Primary.copy(alpha = 0.15f))
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = {},
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            imageVector = fileIcon(file),
            contentDescription = null,
            tint = if (file.isDirectory) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = file.name,
                style = NexaraTypography.bodyLarge,
                color = NexaraColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!file.isDirectory) {
                Text(
                    text = formatFileMetadata(file),
                    style = NexaraTypography.labelSmall,
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }

        IndexStatusBadge(status = resolveIndexStatus(file, indexingFileIds))

        if (!file.isDirectory) {
            Spacer(modifier = Modifier.width(6.dp))
            KgStatusIcon(status = resolveKgStatus(file, kgExtractionStates))
        }
    }
}

@Composable
private fun RenameDialog(
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
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
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
        containerColor = NexaraColors.SurfaceLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.files_move_to), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
            if (directoryList.isEmpty()) {
                Text(
                    stringResource(R.string.files_no_available_folders),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant,
                )
            }
            // 根目录选项
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { onSelect(workspaceRootUuid) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.files_root_directory), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
            }
            directoryList.forEach { dir ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { onSelect(dir.uuid) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(dir.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                }
            }
        }
    }
}

@Composable
private fun EmptyFilesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
private fun formatFileMetadata(file: FileEntry): String {
    val size = formatFileSize(file.sizeBytes)
    val time = formatRelativeTime(file.updatedAt)
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
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("MMM d", locale).format(Date(timestamp))
    }
}
