package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityShapes
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography

/**
 * 全站唯一的对话框：确认、提示与单字段输入都用它，自带窗口，不再外包 `Dialog`。
 *
 * 点外部与返回调用 [onDismissRequest]（默认同取消）；[content] 放在正文与按钮之间，用于输入框或补充说明。
 * [cancelText] 为 null 时只有确认按钮（纯提示）；[title] 为空时只显示正文。
 */
@Composable
fun InfinityConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String? = stringResource(R.string.common_btn_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
    confirmButtonModifier: Modifier = Modifier,
    cancelButtonModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    onDismissRequest: () -> Unit = onCancel,
    properties: DialogProperties = DialogProperties(),
    content: @Composable (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        InfinityConfirmDialogCard(
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = onConfirm,
            onCancel = onCancel,
            isDestructive = isDestructive,
            confirmButtonModifier = confirmButtonModifier,
            cancelButtonModifier = cancelButtonModifier,
            modifier = modifier,
            confirmEnabled = confirmEnabled,
            cancelEnabled = cancelEnabled,
            content = content,
        )
    }
}

/** 对话框卡片本身，不含窗口；截图测试与预览直接渲染它。 */
@Composable
internal fun InfinityConfirmDialogCard(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String? = stringResource(R.string.common_btn_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
    confirmButtonModifier: Modifier = Modifier,
    cancelButtonModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    content: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .clip(InfinityShapes.extraLarge)
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
                InfinityShapes.extraLarge
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = InfinityShapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // 纯提示可以没有标题，此时正文直接在顶部。
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = InfinityTypography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (message.isNotEmpty()) {
                if (title.isNotBlank()) Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (content != null) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (cancelText != null) {
                    TextButton(
                        onClick = onCancel,
                        enabled = cancelEnabled,
                        modifier = cancelButtonModifier,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = cancelText,
                            style = InfinityTypography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = confirmButtonModifier,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDestructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isDestructive) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    shape = InfinityShapes.medium
                ) {
                    Text(
                        text = confirmText,
                        style = InfinityTypography.labelMedium
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityConfirmDialogPreview() {
    InfinityTheme {
        InfinityConfirmDialogCard(
            title = "Delete Session",
            message = "Are you sure you want to delete this session? This action cannot be undone.",
            confirmText = "Delete",
            onConfirm = {},
            onCancel = {},
            isDestructive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityConfirmDialogNormalPreview() {
    InfinityTheme {
        InfinityConfirmDialogCard(
            title = "Clear Cache",
            message = "This will clear all cached data. Your sessions will not be affected.",
            confirmText = "Clear",
            onConfirm = {},
            onCancel = {},
            isDestructive = false
        )
    }
}
