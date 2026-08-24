package com.promenar.nexara.share.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareIndexStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.ShareTargetKind
import com.promenar.nexara.ui.testing.UiTags
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportSheet(
    state: ShareImportUiState,
    onSelectTarget: (String) -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit = {},
) {
    if (!state.visible) return
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.share_import_cancel_title)) },
            text = { Text(stringResource(R.string.share_import_cancel_message)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onCancel() }) {
                    Text(stringResource(R.string.share_import_cancel_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.share_import_cancel_back))
                }
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = Modifier
            .testTag(UiTags.SHARE_IMPORT_SHEET)
            .semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.share_import_title))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.share_import_target_label))
            state.targets.forEach { target ->
                val targetModifier = if (target.kind == ShareTargetKind.KnowledgeBase) {
                    Modifier.testTag(UiTags.SHARE_IMPORT_KNOWLEDGE_BASE_TARGET)
                } else {
                    Modifier
                }
                Row(
                    modifier = targetModifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(enabled = !state.importing) {
                            onSelectTarget(target.workspaceRootUuid)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.selectedWorkspaceRootUuid == target.workspaceRootUuid,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(target.label)
                }
            }
            if (state.targets.isEmpty()) {
                Text(stringResource(R.string.share_import_no_target))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.uri.toString() }) { item -> ShareImportItemRow(item) }
            }
            state.error?.let { error ->
                Text(
                    text = stringResource(error.stringResource()),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onClose,
                    enabled = !state.importing,
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_LATER).height(48.dp),
                ) {
                    Text(stringResource(R.string.share_import_later))
                }
                TextButton(
                    onClick = { confirmCancel = true },
                    enabled = !state.importing,
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_CANCEL).height(48.dp),
                ) {
                    Text(stringResource(R.string.share_import_cancel))
                }
                if (state.error != null ||
                    state.items.any { it.status == ShareImportStatus.Rejected && it.reason?.retryable == true } ||
                    state.items.any { it.indexStatus in setOf(ShareIndexStatus.Failed, ShareIndexStatus.Partial) }
                ) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !state.importing && (
                            state.selectedWorkspaceRootUuid != null ||
                                state.error in setOf(
                                    ShareImportErrorCode.PRESENT_FAILED,
                                    ShareImportErrorCode.POSTPONE_FAILED,
                                    ShareImportErrorCode.CANCEL_FAILED,
                                )
                            ),
                        modifier = Modifier.height(48.dp),
                    ) { Text(stringResource(R.string.share_import_retry)) }
                }
                Button(
                    onClick = onImport,
                    enabled = !state.importing && state.selectedWorkspaceRootUuid != null &&
                        state.items.any { it.status == ShareImportStatus.Pending },
                    modifier = Modifier.testTag(UiTags.SHARE_IMPORT_ACTION).height(48.dp),
                ) {
                    Text(
                        if (state.importing) stringResource(R.string.share_import_importing)
                        else stringResource(R.string.share_import_action)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun ShareImportErrorCode.stringResource(): Int = when (this) {
    ShareImportErrorCode.PRESENT_FAILED -> R.string.share_import_error_present
    ShareImportErrorCode.IMPORT_FAILED -> R.string.share_import_error_import
    ShareImportErrorCode.INDEX_RETRY_FAILED -> R.string.share_import_error_index_retry
    ShareImportErrorCode.POSTPONE_FAILED -> R.string.share_import_error_postpone
    ShareImportErrorCode.CANCEL_FAILED -> R.string.share_import_error_cancel
}

@Composable
fun SharePendingBanner(
    pendingCount: Int,
    visible: Boolean,
    onOpen: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            onClick = onOpen,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Text(
                text = stringResource(R.string.share_import_pending_banner, pendingCount),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun ShareImportItemRow(item: ShareImportItem) {
    Column(Modifier.testTag(UiTags.SHARE_IMPORT_ITEM).fillMaxWidth()) {
        Text(item.displayName)
        Text(
            listOfNotNull(
                compactMimeType(item.mimeType),
                item.sizeBytes?.let(::formatShareBytes),
                statusLabel(item),
            ).joinToString(" · "),
            modifier = Modifier.testTag(UiTags.SHARE_IMPORT_STATUS),
        )
        item.reason?.let { Text(rejectReasonLabel(it)) }
    }
}

internal fun compactMimeType(mimeType: String?): String? {
    if (mimeType == null) return null
    return when (mimeType.lowercase(Locale.ROOT)) {
        "application/pdf" -> "PDF"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
        "text/plain" -> "TXT"
        else -> mimeType
    }
}

@Composable
private fun statusLabel(item: ShareImportItem): String = when (item.status) {
    ShareImportStatus.Pending -> stringResource(R.string.share_import_status_pending)
    ShareImportStatus.Importing -> stringResource(R.string.share_import_status_importing)
    ShareImportStatus.Created -> when (item.indexStatus) {
        ShareIndexStatus.Pending -> stringResource(R.string.share_import_status_index_pending)
        ShareIndexStatus.Partial -> stringResource(R.string.share_import_status_index_partial)
        ShareIndexStatus.Failed -> stringResource(R.string.share_import_status_index_failed)
        ShareIndexStatus.Completed -> stringResource(R.string.share_import_status_index_completed)
        null -> stringResource(R.string.share_import_status_created)
    }
    ShareImportStatus.Rejected -> stringResource(R.string.share_import_status_rejected)
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason): String = stringResource(
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
    }
)

private fun formatShareBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
