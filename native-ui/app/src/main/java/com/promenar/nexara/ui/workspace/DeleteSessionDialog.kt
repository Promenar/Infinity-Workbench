package com.promenar.nexara.ui.workspace

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import com.promenar.nexara.R
import com.promenar.nexara.data.session.SessionDeletionResult
import com.promenar.nexara.domain.workspace.PrivateWorkspaceSummary
import com.promenar.nexara.ui.common.NexaraConfirmDialogCard
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import kotlinx.coroutines.CancellationException

/**
 * 删除会话确认框。会话专用空间里有文件时让用户选择保留（默认，存到内置文件系统）或一并删除；
 * [loadSummary] 未返回前按“保留”处理，后端在空间为空时直接删除。
 */
@Composable
fun DeleteSessionDialog(
    sessionId: String,
    loadSummary: (suspend (String) -> PrivateWorkspaceSummary?)?,
    onConfirm: (keepFiles: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var summary by remember(sessionId) { mutableStateOf<PrivateWorkspaceSummary?>(null) }
    var keep by rememberSaveable(sessionId) { mutableStateOf(true) }
    LaunchedEffect(sessionId, loadSummary) {
        summary = try {
            loadSummary?.invoke(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Dialog(onDismissRequest = onCancel) {
        DeleteSessionDialogContent(summary, keep, onKeepChange = { keep = it }, onConfirm = { onConfirm(keep) }, onCancel = onCancel)
    }
}

/** 保留了文件时提示保存位置；会话页删除后会立即返回，因此用 Toast 而不是页内提示。 */
fun announceKeptFiles(context: Context, result: SessionDeletionResult) {
    val kept = result as? SessionDeletionResult.DeletedKeepingFiles ?: return
    val text = context.getString(R.string.delete_session_kept, context.getString(R.string.workspace_kind_project), kept.displayName)
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

@Composable
internal fun DeleteSessionDialogContent(
    summary: PrivateWorkspaceSummary?,
    keep: Boolean,
    onKeepChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    NexaraConfirmDialogCard(
        title = stringResource(R.string.chat_dialog_delete_session_title),
        message = if (summary == null) {
            stringResource(R.string.chat_dialog_delete_session_msg)
        } else {
            pluralStringResource(R.plurals.delete_session_files, summary.fileCount, summary.fileCount) +
                " · " + Formatter.formatShortFileSize(context, summary.totalBytes)
        },
        confirmText = stringResource(R.string.shared_btn_delete),
        onConfirm = onConfirm,
        onCancel = onCancel,
        isDestructive = true,
        confirmButtonModifier = Modifier.testTag("delete_session_confirm"),
    ) {
        AnimatedVisibility(visible = summary != null, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                KeepOption(
                    selected = keep,
                    label = stringResource(R.string.delete_session_keep_files),
                    detail = stringResource(R.string.delete_session_keep_files_to, stringResource(R.string.workspace_kind_project)),
                    onClick = { onKeepChange(true) },
                    tag = "delete_session_keep",
                )
                KeepOption(
                    selected = !keep,
                    label = stringResource(R.string.delete_session_drop_files),
                    detail = null,
                    onClick = { onKeepChange(false) },
                    tag = "delete_session_drop",
                )
            }
        }
    }
}

@Composable
private fun KeepOption(selected: Boolean, label: String, detail: String?, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(NexaraSpacing.Small))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Spacer(Modifier.size(NexaraSpacing.Small))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
