package com.promenar.infinity.ui.workspace

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
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
import com.promenar.infinity.R
import com.promenar.infinity.data.session.SessionDeletionResult
import com.promenar.infinity.domain.workspace.PrivateWorkspaceSummary
import com.promenar.infinity.ui.common.InfinityConfirmDialogCard
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException

/**
 * 删除会话确认框。会话专用空间里有文件时让用户选择保留（默认，存到内置文件系统）或一并删除；
 * [loadSummary] 未返回前按“保留”处理，后端在空间为空时直接删除。
 */
@Composable
fun DeleteSessionDialog(
    sessionId: String,
    loadSummary: (suspend (String) -> PrivateWorkspaceSummary?)?,
    onConfirm: (keepFiles: Boolean, removeWorktree: Boolean) -> Unit,
    onCancel: () -> Unit,
    /** 分支会话绑定的 worktree 分支名；有绑定时对话框询问是否一并移除。 */
    loadWorktreeBranch: (suspend (String) -> String?)? = null,
) {
    var summary by remember(sessionId) { mutableStateOf<PrivateWorkspaceSummary?>(null) }
    var worktreeBranch by remember(sessionId) { mutableStateOf<String?>(null) }
    var keep by rememberSaveable(sessionId) { mutableStateOf(true) }
    var removeWorktree by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, loadSummary) {
        summary = try {
            loadSummary?.invoke(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    LaunchedEffect(sessionId, loadWorktreeBranch) {
        worktreeBranch = try {
            loadWorktreeBranch?.invoke(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Dialog(onDismissRequest = onCancel) {
        DeleteSessionDialogContent(
            summary, keep, onKeepChange = { keep = it },
            onConfirm = { onConfirm(keep, removeWorktree && worktreeBranch != null) }, onCancel = onCancel,
            worktreeBranch = worktreeBranch, removeWorktree = removeWorktree, onRemoveWorktreeChange = { removeWorktree = it },
        )
    }
}

/** 读取会话已绑定的 worktree 分支名；没有绑定时为 null，对话框不显示移除选项。 */
fun worktreeBranchLoader(app: com.promenar.infinity.InfinityApplication): suspend (String) -> String? = { id ->
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        app.database.sessionDao().getById(id)?.takeIf { it.worktreeDirectory != null }?.worktreeBranch
    }
}

/** 删除分支会话前移除它的 worktree：Git 拒绝（例如有未提交改动）时只提示原因，会话照常删除。 */
suspend fun removeBranchWorktree(context: Context, controls: com.promenar.infinity.domain.branch.BranchControls, sessionId: String) {
    controls.removeWorktree(sessionId).onFailure { failure ->
        Toast.makeText(context, context.getString(R.string.delete_session_worktree_failed, failure.message.orEmpty()), Toast.LENGTH_LONG).show()
    }
}

/** 保留了文件时提示保存位置；会话页删除后会立即返回，因此用 Toast 而不是页内提示。 */
fun announceKeptFiles(context: Context, result: SessionDeletionResult) {
    val kept = result as? SessionDeletionResult.DeletedKeepingFiles ?: return
    val text = context.getString(R.string.delete_session_kept, context.getString(R.string.workspace_picker_shared_folder), kept.displayName)
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

@Composable
internal fun DeleteSessionDialogContent(
    summary: PrivateWorkspaceSummary?,
    keep: Boolean,
    onKeepChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    /** 分支会话绑定的 worktree 分支名；为空时不显示移除选项。 */
    worktreeBranch: String? = null,
    removeWorktree: Boolean = false,
    onRemoveWorktreeChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    InfinityConfirmDialogCard(
        title = stringResource(R.string.chat_dialog_delete_session_title),
        message = if (summary == null) stringResource(R.string.chat_dialog_delete_session_msg) else "",
        messageSegments = summary?.let {
            listOf(
                pluralStringResource(R.plurals.delete_session_files, it.fileCount, it.fileCount),
                Formatter.formatShortFileSize(context, it.totalBytes),
            )
        },
        confirmText = stringResource(R.string.shared_btn_delete),
        onConfirm = onConfirm,
        onCancel = onCancel,
        isDestructive = true,
        confirmButtonModifier = Modifier.testTag("delete_session_confirm"),
    ) {
        AnimatedVisibility(visible = summary != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                KeepOption(
                    selected = keep,
                    label = stringResource(R.string.delete_session_keep_files),
                    detail = stringResource(R.string.delete_session_keep_files_to, stringResource(R.string.workspace_picker_shared_folder)),
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
        if (worktreeBranch != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .toggleable(value = removeWorktree, onValueChange = onRemoveWorktreeChange, role = Role.Checkbox)
                    .testTag("delete_session_remove_worktree"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = removeWorktree, onCheckedChange = null)
                Spacer(Modifier.size(InfinitySpacing.Small))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.delete_session_remove_worktree, worktreeBranch), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.delete_session_remove_worktree_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeepOption(selected: Boolean, label: String, detail: String?, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(InfinitySpacing.Small))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Spacer(Modifier.size(InfinitySpacing.Small))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
