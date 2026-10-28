package com.promenar.infinity.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.workspace.FolderNameError
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput

/** 新建项目、新建文件夹与重命名登记共用的名称输入框；[error] 由调用方在提交后给出。 */
@Composable
internal fun WorkspaceNameDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    hint: String? = null,
    error: String? = null,
    busy: Boolean = false,
    onValueChange: () -> Unit = {},
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    InfinityConfirmDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = title,
        message = "",
        confirmText = confirmLabel,
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = { onConfirm(value.trim()) },
        // 忙碌时取消按钮原为禁用；统一对话框无禁用态，改为忙碌期间忽略点击。
        onCancel = onDismiss,
        cancelEnabled = !busy,
        confirmEnabled = !busy && value.isNotBlank(),
        confirmButtonModifier = Modifier.testTag("workspace_name_confirm"),
        content = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onValueChange()
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.forInput(),
                    label = { Text(label) },
                    isError = error != null,
                    supportingText = (error ?: hint)?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth().testTag("workspace_name_input"),
                )
            }
        },
    )
}

@Composable
internal fun folderNameErrorText(error: FolderNameError, project: Boolean): String = when (error) {
    FolderNameError.EMPTY -> stringResource(R.string.workspace_picker_name_empty)
    FolderNameError.INVALID -> stringResource(
        if (project) R.string.workspace_picker_name_invalid_project else R.string.workspace_picker_name_invalid_folder,
    )
    FolderNameError.RESERVED -> stringResource(R.string.workspace_picker_name_reserved)
    FolderNameError.EXISTS -> stringResource(R.string.workspace_picker_name_exists)
}
