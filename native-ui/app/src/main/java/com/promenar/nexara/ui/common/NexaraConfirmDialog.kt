package com.promenar.nexara.ui.common

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
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun NexaraConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String? = stringResource(R.string.common_btn_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
    content: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .clip(NexaraShapes.extraLarge)
            .border(
                0.5.dp,
                NexaraColors.GlassBorder,
                NexaraShapes.extraLarge
            ),
        color = NexaraColors.SurfaceContainer,
        shape = NexaraShapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )

            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
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
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = NexaraColors.OnSurfaceVariant
                        )
                    ) {
                        Text(
                            text = cancelText,
                            style = NexaraTypography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDestructive) {
                            NexaraColors.ErrorContainer
                        } else {
                            NexaraColors.Primary
                        },
                        contentColor = if (isDestructive) {
                            NexaraColors.OnErrorContainer
                        } else {
                            NexaraColors.OnPrimary
                        }
                    ),
                    shape = NexaraShapes.medium
                ) {
                    Text(
                        text = confirmText,
                        style = NexaraTypography.labelMedium
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraConfirmDialogPreview() {
    NexaraTheme {
        NexaraConfirmDialog(
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
private fun NexaraConfirmDialogNormalPreview() {
    NexaraTheme {
        NexaraConfirmDialog(
            title = "Clear Cache",
            message = "This will clear all cached data. Your sessions will not be affected.",
            confirmText = "Clear",
            onConfirm = {},
            onCancel = {},
            isDestructive = false
        )
    }
}
