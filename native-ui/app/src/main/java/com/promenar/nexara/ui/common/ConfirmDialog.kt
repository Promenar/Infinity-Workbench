package com.promenar.nexara.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors

@Composable
fun ConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    description: String,
    confirmLabel: String = stringResource(R.string.common_btn_confirm),
    confirmColor: Color = NexaraColors.Error,
    destructive: Boolean = true
) {
    if (!show) return

    Dialog(onDismissRequest = onDismiss) {
        NexaraConfirmDialog(
            title = title,
            message = description,
            confirmText = confirmLabel,
            onConfirm = onConfirm,
            onCancel = onDismiss,
            isDestructive = destructive
        )
    }
}
