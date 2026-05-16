package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    indexingFileIds: Set<String> = emptySet(),
    folders: List<FileEntry> = emptyList() // 用于"移动到"弹窗的目录列表
) {
    val roots by workspaceRepo.observeRoots().collectAsState(initial = emptyList())

    val filteredRoots = if (searchQuery.isBlank()) roots else {
        roots.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    // 多选状态
    val selectedIds = remember { mutableStateListOf<String>() }
    val isMultiSelectMode = selectedIds.isNotEmpty()

    val content = @Composable { root: FileEntry ->
        FileTreeNode(
            file = root, depth = 0, workspaceRepo = workspaceRepo,
            searchQuery = searchQuery,
            onReindex = onReindex, onDelete = { id ->
                onDelete?.invoke(id); selectedIds.remove(id)
            },
            onRename = onRename, onMove = onMove, onExtractKG = onExtractKG,
            indexingFileIds = indexingFileIds,
            selectedIds = selectedIds, isMultiSelectMode = isMultiSelectMode
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (useScroll) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredRoots, key = { it.uuid }) { root -> content(root) }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filteredRoots.isEmpty() && searchQuery.isBlank()) {
                    EmptyFilesState()
                } else {
                    filteredRoots.forEach { root -> content(root) }
                }
            }
        }

        // 底部批量操作栏
        if (isMultiSelectMode) {
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
                Text("已选 $selectedCount 项", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                TextButton(onClick = onClear) {
                    Text("取消", style = NexaraTypography.labelSmall, color = NexaraColors.Primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onReindexAll != null) {
                    TextButton(onClick = onReindexAll) {
                        Text("重索引", style = NexaraTypography.labelSmall, color = NexaraColors.Primary)
                    }
                }
                if (onDeleteAll != null) {
                    TextButton(onClick = onDeleteAll) {
                        Text("删除", style = NexaraTypography.labelSmall, color = NexaraColors.Error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeNode(
    file: FileEntry,
    depth: Int,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = "",
    onReindex: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onRename: ((String, String) -> Unit)? = null,
    onMove: ((String, String) -> Unit)? = null,
    onExtractKG: ((String) -> Unit)? = null,
    indexingFileIds: Set<String> = emptySet(),
    selectedIds: MutableList<String> = mutableStateListOf(),
    isMultiSelectMode: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(depth < 2) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveSheet by rememberSaveable { mutableStateOf(false) }
    val isSelected = file.uuid in selectedIds

    Box {
        NexaraGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                .combinedClickable(
                    onClick = {
                        if (isMultiSelectMode) {
                            if (isSelected) selectedIds.remove(file.uuid) else selectedIds.add(file.uuid)
                        } else if (file.isDirectory) {
                            expanded = !expanded
                        }
                    },
                    onLongClick = {
                        if (!isMultiSelectMode) {
                            selectedIds.add(file.uuid)
                            showMenu = true
                        }
                    }
                ),
            shape = RoundedCornerShape(12.dp)
        ) {
            FileRow(
                file = file,
                indexingFileIds = indexingFileIds,
                isMultiSelectMode = isMultiSelectMode,
                isSelected = isSelected
            )
        }

        // 长按上下文菜单
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (!file.isDirectory) {
                if (onReindex != null) {
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Refresh, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                                Text("重新索引", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            }
                        },
                        onClick = { showMenu = false; onReindex(file.uuid) }
                    )
                }
                if (onExtractKG != null) {
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AccountTree, null, tint = NexaraColors.Tertiary, modifier = Modifier.size(18.dp))
                                Text("提取知识图谱", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            }
                        },
                        onClick = { showMenu = false; onExtractKG(file.uuid) }
                    )
                }
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Edit, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text("重命名", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                    },
                    onClick = { showMenu = false; showRenameDialog = true }
                )
            }
            if (onMove != null && !file.isDirectory) {
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text("移动到…", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                    },
                    onClick = { showMenu = false; showMoveSheet = true }
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Delete, null, tint = NexaraColors.Error, modifier = Modifier.size(18.dp))
                            Text("删除", style = NexaraTypography.labelMedium, color = NexaraColors.Error)
                        }
                    },
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
            folders = emptyList(), // 由父级通过 workspaceRepo.observeRoots 查询
            workspaceRepo = workspaceRepo,
            onDismiss = { showMoveSheet = false },
            onSelect = { targetUuid ->
                showMoveSheet = false
                onMove?.invoke(file.uuid, targetUuid)
            }
        )
    }

    if (file.isDirectory && expanded) {
        val children by workspaceRepo.observeChildren(file.uuid)
            .collectAsState(initial = emptyList())

        val filteredChildren = if (searchQuery.isBlank()) children else {
            children.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        filteredChildren.forEach { child ->
            FileTreeNode(
                file = child, depth = depth + 1, workspaceRepo = workspaceRepo,
                searchQuery = searchQuery, onReindex = onReindex, onDelete = onDelete,
                onRename = onRename, onMove = onMove, onExtractKG = onExtractKG,
                indexingFileIds = indexingFileIds,
                selectedIds = selectedIds, isMultiSelectMode = isMultiSelectMode
            )
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    indexingFileIds: Set<String> = emptySet(),
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            tint = if (file.isDirectory) NexaraColors.Tertiary else NexaraColors.OnSurfaceVariant,
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
        title = { Text("重命名", style = NexaraTypography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoveToSheet(
    folders: List<FileEntry>,
    workspaceRepo: IWorkspaceRepository,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val dirs by workspaceRepo.observeRoots().collectAsState(initial = emptyList())
    val directoryList = folders.ifEmpty { dirs.filter { it.isDirectory } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NexaraColors.SurfaceLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("移动到…", style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
            if (directoryList.isEmpty()) {
                Text("没有可用的目录", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
            }
            // 根目录选项
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { onSelect("") }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("根目录", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
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
        Text("暂无文件", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
        Text("工作区文件将在 AI 操作时自动创建", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
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

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        else -> { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp)) }
    }
}
