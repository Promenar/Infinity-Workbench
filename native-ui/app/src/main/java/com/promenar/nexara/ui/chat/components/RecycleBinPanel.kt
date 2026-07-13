package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun RecycleBinPanel(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    scopedFiles: List<FileEntry>? = null,
) {
    val recycleFlow = when {
        scopedFiles != null -> flowOf(scopedFiles)
        workspaceRootUuid != null -> workspaceRepo.observeRecycleBin(workspaceRootUuid)
        else -> flowOf(emptyList())
    }
    val recycledFiles by recycleFlow.collectAsState(initial = scopedFiles.orEmpty())

    var showPermanentDeleteConfirm by remember { mutableStateOf<FileEntry?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ActionBar(
            itemCount = recycledFiles.size,
            onRestore = {
                scope.launch {
                    workspaceRootUuid?.let { root ->
                        recycledFiles.forEach { workspaceRepo.restoreFromRecycleBin(root, it.uuid) }
                    }
                }
            },
            onPermanentDelete = {
                showEmptyConfirm = true
            }
        )

        if (recycledFiles.isEmpty()) {
            EmptyRecycleBinState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = recycledFiles,
                    key = { it.uuid }
                ) { file ->
                    RecycleBinItem(
                        file = file,
                        onRestore = {
                            scope.launch {
                                workspaceRootUuid?.let { workspaceRepo.restoreFromRecycleBin(it, file.uuid) }
                            }
                        },
                        onPermanentDelete = {
                            showPermanentDeleteConfirm = file
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.recycle_bin_auto_cleanup),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.RagPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    showPermanentDeleteConfirm?.let { target ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { showPermanentDeleteConfirm = null }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.recycle_bin_permanent_delete_title),
                message = stringResource(R.string.recycle_bin_permanent_delete_message, target.name),
                confirmText = stringResource(R.string.recycle_bin_permanent_delete_title),
                onConfirm = {
                    workspaceRootUuid?.let { root ->
                        scope.launch { workspaceRepo.permanentDelete(root, target.uuid) }
                    }
                    showPermanentDeleteConfirm = null
                },
                onCancel = { showPermanentDeleteConfirm = null },
                isDestructive = true
            )
        }
    }

    if (showEmptyConfirm) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showEmptyConfirm = false }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.recycle_bin_empty_title),
                message = stringResource(R.string.recycle_bin_empty_message),
                confirmText = stringResource(R.string.recycle_bin_empty_confirm),
                onConfirm = {
                    if (workspaceRootUuid != null) {
                        scope.launch { workspaceRepo.emptyRecycleBin(workspaceRootUuid) }
                    }
                    showEmptyConfirm = false
                },
                onCancel = { showEmptyConfirm = false },
                isDestructive = true
            )
        }
    }
}

@Composable
private fun ActionBar(
    itemCount: Int,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionButton(
            label = stringResource(R.string.recycle_bin_restore_all),
            icon = Icons.Rounded.RestoreFromTrash,
            color = NexaraColors.Primary,
            onClick = onRestore,
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            label = stringResource(R.string.recycle_bin_clear_count, itemCount),
            icon = Icons.Rounded.DeleteForever,
            color = NexaraColors.Error,
            onClick = onPermanentDelete,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun RecycleBinItem(
    file: FileEntry,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = NexaraColors.OnSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.name,
                    style = NexaraTypography.bodyLarge,
                    color = NexaraColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                file.originalMaterializedPath?.let { path ->
                    Text(
                        text = stringResource(R.string.recycle_bin_original_path, path),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    file.recycledAt?.let { recycledAt ->
                        Text(
                            text = formatRecycledTime(recycledAt),
                            style = NexaraTypography.labelSmall,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                IndexStatusBadge(status = resolveIndexStatus(file))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = onRestore
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestoreFromTrash,
                            contentDescription = stringResource(R.string.recycle_bin_restore),
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(48.dp),
                        onClick = onPermanentDelete
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteForever,
                            contentDescription = stringResource(R.string.recycle_bin_permanent_delete_title),
                            tint = NexaraColors.Error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRecycleBinState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.DeleteForever,
            contentDescription = null,
            tint = NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recycle_bin_empty_state),
            style = NexaraTypography.labelMedium,
            color = NexaraColors.OnSurface
        )
    }
}

private fun resolveIndexStatus(file: FileEntry): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    return FileIndexStatus.INDEXED
}

@Composable
private fun formatRecycledTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> {
            val sdf = SimpleDateFormat("MMM d", locale)
            sdf.format(Date(timestamp))
        }
    }
}
