package com.promenar.infinity.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R

@Composable
fun ConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    description: String,
    confirmLabel: String = stringResource(R.string.common_btn_confirm),
    confirmColor: Color = MaterialTheme.colorScheme.error,
    destructive: Boolean = true
) {
    if (!show) return

    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = description,
        confirmText = confirmLabel,
        onConfirm = onConfirm,
        onCancel = onDismiss,
        isDestructive = destructive
    )
}
