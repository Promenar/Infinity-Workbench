package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal enum class RagFolderContentState {
    Loading,
    Error,
    Empty,
    Content,
}

internal fun resolveRagFolderContentState(
    hasLoaded: Boolean,
    hasLoadError: Boolean,
    documentCount: Int,
): RagFolderContentState = when {
    hasLoadError -> RagFolderContentState.Error
    !hasLoaded -> RagFolderContentState.Loading
    documentCount == 0 -> RagFolderContentState.Empty
    else -> RagFolderContentState.Content
}

internal fun isAllRagFolderDocumentsSelected(documentCount: Int, selectedCount: Int): Boolean =
    documentCount > 0 && selectedCount == documentCount

internal fun shouldShowRagDocumentCheckbox(): Boolean = true

internal fun ragFolderSelectionBarReservedHeight(hasSelection: Boolean): Int =
    if (hasSelection) 176 else 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagFolderScreen(
    folderId: String,
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateToDocEditor: (String, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val indexingNotice by viewModel.indexingNotice.collectAsState()
    val canRetryLastFailedIndex by viewModel.canRetryLastFailedIndex.collectAsState()
    val isRetryingLastFailedIndex by viewModel.isRetryingLastFailedIndex.collectAsState()
    val isMovingDocuments by viewModel.isMovingDocuments.collectAsState()
    val isDeletingDocuments by viewModel.isDeletingDocuments.collectAsState()
    val selectedIds = remember { mutableStateListOf<String>() }
    var folderEntries by remember(folderId) { mutableStateOf<List<FileEntry>?>(null) }
    var resolvedFolderName by remember(folderId) { mutableStateOf<String?>(null) }
    var folderLoadError by remember(folderId) { mutableStateOf(false) }
    var folderLoadAttempt by remember(folderId) { mutableStateOf(0) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importDocuments(uris, folderId)
        }
    }

    LaunchedEffect(folderId, workspaceRootUuid) {
        resolvedFolderName = null
        val rootUuid = workspaceRootUuid ?: return@LaunchedEffect
        try {
            resolvedFolderName = viewModel.getWorkspaceRepo()
                .getByUuid(rootUuid, folderId)
                ?.name
                ?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 标题读取失败不阻断文件夹内容加载，继续使用本地化回退标题。
        }
    }

    LaunchedEffect(folderId, workspaceRootUuid, folderLoadAttempt) {
        folderEntries = null
        folderLoadError = false
        val rootUuid = workspaceRootUuid ?: return@LaunchedEffect
        try {
            viewModel.getWorkspaceRepo().observeChildren(rootUuid, folderId).collect { entries ->
                folderEntries = entries.filterNot { it.isDirectory }
                selectedIds.retainAll(folderEntries.orEmpty().mapTo(mutableSetOf()) { it.uuid })
                if (showDeleteConfirm && selectedIds.isEmpty()) {
                    showDeleteConfirm = false
                }
                folderLoadError = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            folderLoadError = true
        }
    }
    val documents = folderEntries.orEmpty()
    val contentState = resolveRagFolderContentState(
        hasLoaded = folderEntries != null,
        hasLoadError = folderLoadError,
        documentCount = documents.size,
    )
    val allDocumentsSelected = isAllRagFolderDocumentsSelected(
        documentCount = documents.size,
        selectedCount = selectedIds.size,
    )

    Box(modifier = Modifier.fillMaxSize()) {
    NexaraPageLayout(
        title = resolvedFolderName ?: stringResource(R.string.rag_home_documents),
        onBack = onNavigateBack,
        scrollable = false, // 内容包含 LazyColumn，禁用外层滚动避免冲突
        actions = {
            IconButton(onClick = {
                if (allDocumentsSelected) {
                    selectedIds.clear()
                } else {
                    selectedIds.clear()
                    selectedIds.addAll(documents.map { it.uuid })
                }
            }, enabled = documents.isNotEmpty()) {
                Text(
                    if (allDocumentsSelected) stringResource(R.string.rag_folder_deselect) else stringResource(R.string.rag_folder_select_all),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(
                bottom = ragFolderSelectionBarReservedHeight(selectedIds.isNotEmpty()).dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isIndexing || indexingNotice != null) {
                val resolvedNotice = indexingNotice?.let(IndexingNotice::template)
                val statusText = resolvedNotice?.let { resolved ->
                    stringResource(resolved.resourceId, *resolved.args.toTypedArray())
                } ?: stringResource(R.string.rag_index_phase_unknown)
                val isError = indexingNotice?.severity == NoticeSeverity.Error
                val retryAction: (() -> Unit)? = when {
                    canRetryLastFailedIndex -> viewModel::retryLastFailedIndex
                    indexingNotice?.code == IndexingNotice.CODE_IMPORT_FAILED -> {
                        { filePickerLauncher.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) }
                    }
                    indexingNotice?.code == IndexingNotice.CODE_MOVE_FAILED && selectedIds.isNotEmpty() -> {
                        { showMoveSheet = true }
                    }
                    indexingNotice?.code == IndexingNotice.CODE_DELETE_FAILED && selectedIds.isNotEmpty() -> {
                        { showDeleteConfirm = true }
                    }
                    indexingNotice?.code in setOf(IndexingNotice.CODE_FAILED, IndexingNotice.CODE_WARNING) &&
                        selectedIds.isNotEmpty() -> {
                        { viewModel.reindexDocuments(selectedIds.toList()) }
                    }
                    else -> null
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IndexingProgressBar(
                        progress = indexingProgress,
                        statusText = statusText,
                        subStatusText = if (retryAction != null && !isRetryingLastFailedIndex) {
                            stringResource(R.string.rag_index_retry_hint)
                        } else {
                            null
                        },
                        isError = isError,
                    )
                    if (indexingNotice != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            retryAction?.let { retry ->
                                TextButton(
                                    onClick = retry,
                                    enabled = !isRetryingLastFailedIndex,
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                ) {
                                    Text(stringResource(R.string.shared_btn_retry))
                                }
                            }
                            TextButton(
                                onClick = viewModel::dismissQueueError,
                                enabled = !isRetryingLastFailedIndex,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Text(stringResource(R.string.common_dismiss))
                            }
                        }
                    }
                }
            }

            when (contentState) {
                RagFolderContentState.Loading -> RagFolderLoadingState()
                RagFolderContentState.Error -> RagFolderLoadErrorState(
                    onRetry = { folderLoadAttempt += 1 },
                )
                RagFolderContentState.Empty -> {
                    NexaraGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaraShapes.large as RoundedCornerShape
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                tint = NexaraColors.OnSurfaceVariant,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = stringResource(R.string.rag_folder_empty),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurface
                            )
                            Text(
                                text = stringResource(R.string.rag_folder_empty_subtitle),
                                style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                }
                RagFolderContentState.Content -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(documents, key = { it.uuid }) { doc ->
                            val status = if (doc.vectorizedAt != null) RagStatus.READY else RagStatus.PENDING
                            val isSelected = selectedIds.contains(doc.uuid)

                            RagDocItem(
                                title = doc.name.ifBlank { doc.uuid },
                                status = status,
                                isSelected = isSelected,
                                showCheckbox = shouldShowRagDocumentCheckbox(),
                                onCheckedChange = { checked ->
                                    if (checked && doc.uuid !in selectedIds) selectedIds.add(doc.uuid)
                                    else selectedIds.remove(doc.uuid)
                                },
                                fileSize = formatFileSize(doc.sizeBytes),
                                date = formatDate(doc.updatedAt),
                                onClick = {
                                    workspaceRootUuid?.let { rootUuid ->
                                        onNavigateToDocEditor(rootUuid, doc.uuid)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { filePickerLauncher.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaraColors.SurfaceHigh,
                    contentColor = NexaraColors.Primary
                )
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = NexaraColors.Primary
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                Text(
                    stringResource(R.string.rag_folder_upload),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary
                )
            }

        }
    }

    if (selectedIds.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NexaraColors.SurfaceLow.copy(alpha = 0.95f))
                    .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable(
                                enabled = !isMovingDocuments && !isDeletingDocuments,
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.rag_home_clear_all)
                            ) { selectedIds.clear() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.rag_home_clear_all),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.Primary
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.rag_home_selected_count, selectedIds.size),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.SurfaceContainer)
                        .clickable(
                            enabled = !isMovingDocuments && !isDeletingDocuments,
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.rag_folder_cd_move)
                        ) { showMoveSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = NexaraColors.OnSurface,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.rag_home_move), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable(
                                enabled = !isMovingDocuments && !isDeletingDocuments,
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.rag_folder_cd_reindex)
                            ) { viewModel.reindexDocuments(selectedIds.toList()) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = NexaraColors.OnSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(R.string.rag_home_reindex),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurface
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.Error.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clickable(
                                enabled = !isMovingDocuments && !isDeletingDocuments,
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.shared_btn_delete)
                            ) { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = NexaraColors.Error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(R.string.shared_btn_delete),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Error
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (showMoveSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (!isMovingDocuments) showMoveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.rag_home_move_to_folder), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                folders.forEach { folder ->
                    if (folder.id != folderId) {
                        val moveToName = stringResource(R.string.rag_folder_cd_move_to, folder.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(NexaraColors.SurfaceContainer)
                                .clickable(
                                    enabled = !isMovingDocuments,
                                    role = Role.Button,
                                    onClickLabel = moveToName,
                                ) {
                                    val movingIds = selectedIds.toList()
                                    if (movingIds.isEmpty()) return@clickable
                                    viewModel.moveDocuments(
                                        uuids = movingIds,
                                        targetParentUuid = folder.id,
                                    ) { allSucceeded, failedIds ->
                                        applyMoveSelectionResult(
                                            selectedIds = selectedIds,
                                            movingIds = movingIds.toSet(),
                                            failedIds = failedIds.toSet(),
                                            succeeded = allSucceeded,
                                        )
                                        if (allSucceeded) {
                                            showMoveSheet = false
                                        }
                                    }
                                }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = NexaraColors.OnSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(folder.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ModalBottomSheet(
            onDismissRequest = { if (!isDeletingDocuments) showDeleteConfirm = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.rag_folder_delete_confirm_title, selectedIds.size), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                Text(stringResource(R.string.shared_action_cannot_undo), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable(enabled = !isDeletingDocuments) { showDeleteConfirm = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(R.string.common_btn_cancel), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) }
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Error)
                            .clickable(enabled = !isDeletingDocuments) {
                                val deletingIds = selectedIds.toList()
                                viewModel.deleteDocuments(deletingIds) { succeeded, failedIds ->
                                    applyMoveSelectionResult(
                                        selectedIds = selectedIds,
                                        movingIds = deletingIds.toSet(),
                                        failedIds = failedIds.toSet(),
                                        succeeded = succeeded,
                                    )
                                    showDeleteConfirm = false
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.OnError) }
                }
            }
        }
    }
}

@Composable
private fun RagFolderLoadingState() {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.shared_loading),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RagFolderLoadErrorState(onRetry: () -> Unit) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.common_cd_failed),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.Error,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.shared_btn_retry))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
