package com.promenar.infinity.ui.common

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
import androidx.compose.material3.MaterialTheme
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
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

enum class SnackbarType(val icon: ImageVector, val iconColor: Color) {
    SUCCESS(Icons.Rounded.CheckCircle, Color.Unspecified),
    ERROR(Icons.Rounded.Error, Color.Unspecified),
    INFO(Icons.Rounded.Info, Color.Unspecified)
}

@Stable
data class InfinitySnackbarData(
    val message: String,
    val type: SnackbarType = SnackbarType.INFO,
    val actionLabel: String? = null
)

@Composable
fun InfinitySnackbarHost(
    hostState: SnackbarHostState,
    snackbarData: InfinitySnackbarData?,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        if (snackbarData != null) {
            InfinitySnackbar(
                data = snackbarData,
                onAction = onAction
            )
        }
    }
}

@Composable
fun InfinitySnackbar(
    data: InfinitySnackbarData,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier.padding(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        action = {
            if (data.actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = data.actionLabel,
                        color = MaterialTheme.colorScheme.primary,
                        style = InfinityTypography.labelMedium
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
                tint = if (data.type.iconColor == Color.Unspecified) {
                    when (data.type) {
                        SnackbarType.SUCCESS -> MaterialTheme.infinityDomainColors.success
                        SnackbarType.ERROR -> MaterialTheme.colorScheme.error
                        SnackbarType.INFO -> MaterialTheme.infinityDomainColors.info
                    }
                } else {
                    data.type.iconColor
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = data.message,
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinitySnackbarSuccessPreview() {
    InfinityTheme {
        InfinitySnackbar(
            data = InfinitySnackbarData(
                message = "File uploaded successfully",
                type = SnackbarType.SUCCESS
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinitySnackbarErrorPreview() {
    InfinityTheme {
        InfinitySnackbar(
            data = InfinitySnackbarData(
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
private fun InfinitySnackbarInfoPreview() {
    InfinityTheme {
        InfinitySnackbar(
            data = InfinitySnackbarData(
                message = "Sync in progress...",
                type = SnackbarType.INFO
            )
        )
    }
}
