package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraTypography

enum class SnackbarType(val icon: ImageVector, val iconColor: Color) {
    SUCCESS(Icons.Rounded.CheckCircle, NexaraColors.StatusSuccess),
    ERROR(Icons.Rounded.Error, NexaraColors.StatusError),
    INFO(Icons.Rounded.Info, NexaraColors.StatusInfo)
}

@Stable
data class NexaraSnackbarData(
    val message: String,
    val type: SnackbarType = SnackbarType.INFO,
    val actionLabel: String? = null
)

@Composable
fun NexaraSnackbarHost(
    hostState: SnackbarHostState,
    snackbarData: NexaraSnackbarData?,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        if (snackbarData != null) {
            NexaraSnackbar(
                data = snackbarData,
                onAction = onAction
            )
        }
    }
}

@Composable
fun NexaraSnackbar(
    data: NexaraSnackbarData,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier.padding(16.dp),
        containerColor = NexaraColors.SurfaceHigh,
        shape = RoundedCornerShape(12.dp),
        action = {
            if (data.actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = data.actionLabel,
                        color = NexaraColors.Primary,
                        style = NexaraTypography.labelMedium
                    )
                }
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = data.type.icon,
                contentDescription = null,
                tint = data.type.iconColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = data.message,
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraSnackbarSuccessPreview() {
    NexaraTheme {
        NexaraSnackbar(
            data = NexaraSnackbarData(
                message = "File uploaded successfully",
                type = SnackbarType.SUCCESS
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraSnackbarErrorPreview() {
    NexaraTheme {
        NexaraSnackbar(
            data = NexaraSnackbarData(
                message = "Connection failed",
                type = SnackbarType.ERROR,
                actionLabel = "Retry"
            ),
            onAction = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraSnackbarInfoPreview() {
    NexaraTheme {
        NexaraSnackbar(
            data = NexaraSnackbarData(
                message = "Sync in progress...",
                type = SnackbarType.INFO
            )
        )
    }
}
