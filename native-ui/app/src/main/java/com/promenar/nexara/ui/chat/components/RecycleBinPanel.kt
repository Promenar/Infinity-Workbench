package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun RecycleBinPanel(
    files: List<FileEntry>,
    operationState: RecycleOperationState,
    onRestoreFiles: (List<FileEntry>) -> Unit,
    onPermanentlyDeleteFiles: (List<FileEntry>) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onRetryOperation: () -> Unit,
    onClearOperationState: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
) {
    var showPermanentDeleteConfirm by remember { mutableStateOf<FileEntry?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    val operationRunning = operationState is RecycleOperationState.Running

    Column(modifier = Modifier.fillMaxSize()) {
        if (operationState !is RecycleOperationState.Idle) {
            RecycleOperationNotice(
                state = operationState,
                onRetry = onRetryOperation,
                onClear = onClearOperationState,
            )
        }

        if (files.isEmpty()) {
            EmptyRecycleBinState()
        } else {
            ActionBar(
                itemCount = files.size,
                enabled = !operationRunning,
                onRestore = { onRestoreFiles(files) },
                onPermanentDelete = { showEmptyConfirm = true },
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = files, key = { it.uuid }) { file ->
                    RecycleBinItem(
                        file = file,
                        enabled = !operationRunning,
                        nowMillis = nowMillis,
                        onRestore = { onRestoreFiles(listOf(file)) },
                        onPermanentDelete = { showPermanentDeleteConfirm = file },
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
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    showPermanentDeleteConfirm?.let { target ->
        RecycleBinPermanentDeleteDialog(
            target = target,
            onConfirm = {
                onPermanentlyDeleteFiles(listOf(target))
                showPermanentDeleteConfirm = null
            },
            onDismiss = { showPermanentDeleteConfirm = null },
        )
    }

    if (showEmptyConfirm) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showEmptyConfirm = false }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.recycle_bin_empty_title),
                message = stringResource(R.string.recycle_bin_empty_message),
                confirmText = stringResource(R.string.recycle_bin_empty_confirm),
                onConfirm = {
                    onEmptyRecycleBin()
                    showEmptyConfirm = false
                },
                onCancel = { showEmptyConfirm = false },
                isDestructive = true,
                confirmButtonModifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(TAG_EMPTY_CONFIRM),
            )
        }
    }
}

@Composable
internal fun RecycleBinPermanentDeleteDialog(
    target: FileEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        NexaraConfirmDialog(
            title = stringResource(R.string.recycle_bin_permanent_delete_title),
            message = stringResource(R.string.recycle_bin_permanent_delete_message, target.name),
            confirmText = stringResource(R.string.recycle_bin_permanent_delete_title),
            onConfirm = onConfirm,
            onCancel = onDismiss,
            isDestructive = true,
            confirmButtonModifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(TAG_DELETE_CONFIRM),
        )
    }
}

@Composable
private fun ActionBar(
    itemCount: Int,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        val stackActions = maxWidth < 480.dp || LocalDensity.current.fontScale >= 1.5f
        val contentModifier = Modifier.fillMaxWidth()
        if (stackActions) {
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = stringResource(R.string.recycle_bin_restore_all),
                    icon = Icons.Rounded.RestoreFromTrash,
                    color = NexaraColors.Primary,
                    enabled = enabled,
                    onClick = onRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_RESTORE_ALL),
                )
                ActionButton(
                    label = stringResource(R.string.recycle_bin_clear_count, itemCount),
                    icon = Icons.Rounded.DeleteForever,
                    color = NexaraColors.Error,
                    enabled = enabled,
                    onClick = onPermanentDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_DELETE_ALL),
                )
            }
        } else {
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = stringResource(R.string.recycle_bin_restore_all),
                    icon = Icons.Rounded.RestoreFromTrash,
                    color = NexaraColors.Primary,
                    enabled = enabled,
                    onClick = onRestore,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TAG_RESTORE_ALL),
                )
                ActionButton(
                    label = stringResource(R.string.recycle_bin_clear_count, itemCount),
                    icon = Icons.Rounded.DeleteForever,
                    color = NexaraColors.Error,
                    enabled = enabled,
                    onClick = onPermanentDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TAG_DELETE_ALL),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (enabled) 0.1f else 0.05f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color.copy(alpha = if (enabled) 1f else 0.45f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = color.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecycleOperationNotice(
    state: RecycleOperationState,
    onRetry: () -> Unit,
    onClear: () -> Unit,
) {
    val message = recycleOperationMessage(state)
    val isRunning = state is RecycleOperationState.Running
    val hasFailure = state is RecycleOperationState.PartialFailure || state is RecycleOperationState.Failure
    val statusColor = if (hasFailure) NexaraColors.Error else NexaraColors.Primary

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_OPERATION_STATUS)
            .semantics {
                liveRegion = if (hasFailure) LiveRegionMode.Assertive else LiveRegionMode.Polite
                stateDescription = message
            },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackRunningStatus = isRunning &&
                    (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f)
                if (stackRunningStatus) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = statusColor,
                        )
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth(),
                            style = NexaraTypography.bodyMedium,
                            color = statusColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = statusColor,
                            )
                        }
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = NexaraTypography.bodyMedium,
                            color = statusColor,
                        )
                    }
                }
            }

            if (!isRunning) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasFailure) {
                        StatusActionButton(
                            label = stringResource(R.string.recycle_bin_operation_retry),
                            tag = TAG_OPERATION_RETRY,
                            color = NexaraColors.Primary,
                            onClick = onRetry,
                        )
                    }
                    StatusActionButton(
                        label = stringResource(R.string.recycle_bin_operation_dismiss),
                        tag = TAG_OPERATION_CLEAR,
                        color = NexaraColors.OnSurfaceVariant,
                        onClick = onClear,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusActionButton(
    label: String,
    tag: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = NexaraTypography.labelMedium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun recycleOperationMessage(state: RecycleOperationState): String = when (state) {
    RecycleOperationState.Idle -> ""
    is RecycleOperationState.Running -> stringResource(
        when (state.operation) {
            RecycleOperation.Restore -> R.string.recycle_bin_status_restoring
            RecycleOperation.PermanentDelete -> R.string.recycle_bin_status_deleting
            RecycleOperation.Empty -> R.string.recycle_bin_status_emptying
        },
    )
    is RecycleOperationState.Success -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_success,
            state.processedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_success,
            state.processedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_success)
    }
    is RecycleOperationState.PartialFailure -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_partial,
            state.succeededItemUuids.size,
            state.failedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_partial,
            state.succeededItemUuids.size,
            state.failedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_failed)
    }
    is RecycleOperationState.Failure -> when (state.operation) {
        RecycleOperation.Restore -> stringResource(
            R.string.recycle_bin_status_restore_failed,
            state.failedItemUuids.size,
        )
        RecycleOperation.PermanentDelete -> stringResource(
            R.string.recycle_bin_status_delete_failed,
            state.failedItemUuids.size,
        )
        RecycleOperation.Empty -> stringResource(R.string.recycle_bin_status_empty_failed)
    }
}

@Composable
private fun RecycleBinItem(
    file: FileEntry,
    enabled: Boolean,
    nowMillis: Long,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = NexaraColors.OnSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = file.name,
                    style = NexaraTypography.bodyLarge,
                    color = NexaraColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                file.originalMaterializedPath?.let { path ->
                    Text(
                        text = stringResource(R.string.recycle_bin_original_path, path),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                file.recycledAt?.let { recycledAt ->
                    Text(
                        text = formatRecycledTime(recycledAt, nowMillis),
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End,
            ) {
                IndexStatusBadge(status = resolveIndexStatus(file))

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(restoreTag(file.uuid)),
                        enabled = enabled,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = NexaraColors.Primary,
                            disabledContentColor = NexaraColors.Primary.copy(alpha = 0.38f),
                        ),
                        onClick = onRestore,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestoreFromTrash,
                            contentDescription = stringResource(R.string.recycle_bin_restore),
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(deleteTag(file.uuid)),
                        enabled = enabled,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = NexaraColors.Error,
                            disabledContentColor = NexaraColors.Error.copy(alpha = 0.38f),
                        ),
                        onClick = onPermanentDelete,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteForever,
                            contentDescription = stringResource(R.string.recycle_bin_permanent_delete_title),
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(20.dp),
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
            .testTag(TAG_EMPTY_STATE)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.DeleteForever,
            contentDescription = null,
            tint = NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recycle_bin_empty_state),
            style = NexaraTypography.labelMedium,
            color = NexaraColors.OnSurface,
        )
    }
}

private fun resolveIndexStatus(file: FileEntry): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    return FileIndexStatus.INDEXED
}

@Composable
private fun formatRecycledTime(timestamp: Long, nowMillis: Long): String {
    val diff = (nowMillis - timestamp).coerceAtLeast(0L)
    val locale = LocalLocale.current.platformLocale
    return when {
        diff < 60_000L -> stringResource(R.string.files_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.files_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.files_time_hours_ago, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.files_time_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("MMM d", locale).format(Date(timestamp))
    }
}

private const val TAG_RESTORE_ALL = "recycle_bin_restore_all"
private const val TAG_DELETE_ALL = "recycle_bin_delete_all"
private const val TAG_EMPTY_STATE = "recycle_bin_empty_state"
private const val TAG_OPERATION_STATUS = "recycle_bin_operation_status"
private const val TAG_OPERATION_RETRY = "recycle_bin_operation_retry"
private const val TAG_OPERATION_CLEAR = "recycle_bin_operation_clear"
private const val TAG_DELETE_CONFIRM = "recycle_bin_delete_confirm"
private const val TAG_EMPTY_CONFIRM = "recycle_bin_empty_confirm"

private fun restoreTag(uuid: String) = "recycle_bin_restore:$uuid"
private fun deleteTag(uuid: String) = "recycle_bin_delete:$uuid"
