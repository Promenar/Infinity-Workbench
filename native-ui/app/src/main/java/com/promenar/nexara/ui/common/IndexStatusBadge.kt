package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R

enum class FileIndexStatus {
    INDEXED,
    INDEXING,
    STALE,
    NOT_INDEXED,
    FAILED,
}

@StringRes
internal fun FileIndexStatus.labelResource(): Int = when (this) {
    FileIndexStatus.INDEXED -> R.string.rag_status_ready
    FileIndexStatus.INDEXING -> R.string.rag_status_indexing
    FileIndexStatus.STALE -> R.string.rag_status_pending
    FileIndexStatus.NOT_INDEXED -> R.string.rag_status_pending
    FileIndexStatus.FAILED -> R.string.rag_status_error
}

private data class FileStatusVisuals(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
)

@Composable
private fun FileIndexStatus.visuals(): FileStatusVisuals {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        FileIndexStatus.INDEXED -> FileStatusVisuals(
            icon = Icons.Rounded.CheckCircle,
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        )
        FileIndexStatus.INDEXING -> FileStatusVisuals(
            icon = Icons.Rounded.Sync,
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        )
        FileIndexStatus.STALE -> FileStatusVisuals(
            icon = Icons.Rounded.Sync,
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        )
        FileIndexStatus.NOT_INDEXED -> FileStatusVisuals(
            icon = Icons.Rounded.HourglassEmpty,
            containerColor = colors.surfaceContainerHighest,
            contentColor = colors.onSurfaceVariant,
        )
        FileIndexStatus.FAILED -> FileStatusVisuals(
            icon = Icons.Rounded.Error,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
        )
    }
}

@Composable
fun IndexStatusBadge(
    status: FileIndexStatus,
    modifier: Modifier = Modifier,
) {
    val statusLabel = stringResource(status.labelResource())
    val visuals = status.visuals()

    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = statusLabel
        },
        shape = MaterialTheme.shapes.small,
        color = visuals.containerColor,
        contentColor = visuals.contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = visuals.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

enum class KgStatus {
    COMPLETED,
    IN_PROGRESS,
    FAILED,
    NOT_STARTED,
}

@StringRes
internal fun KgStatus.descriptionResource(): Int = when (this) {
    KgStatus.COMPLETED -> R.string.common_cd_success
    KgStatus.IN_PROGRESS -> R.string.rag_status_indexing
    KgStatus.FAILED -> R.string.common_cd_failed
    KgStatus.NOT_STARTED -> R.string.rag_status_pending
}

@Composable
fun KgStatusIcon(
    status: KgStatus,
    modifier: Modifier = Modifier,
) {
    val statusDescription = buildString {
        append(stringResource(R.string.kg_title))
        append(": ")
        append(stringResource(status.descriptionResource()))
    }
    val colors = MaterialTheme.colorScheme
    val icon = when (status) {
        KgStatus.COMPLETED -> Icons.Rounded.CheckCircle
        KgStatus.IN_PROGRESS -> Icons.Rounded.Sync
        KgStatus.FAILED -> Icons.Rounded.Error
        KgStatus.NOT_STARTED -> Icons.Rounded.AccountTree
    }
    val tint = when (status) {
        KgStatus.COMPLETED -> colors.tertiary
        KgStatus.IN_PROGRESS -> colors.primary
        KgStatus.FAILED -> colors.error
        KgStatus.NOT_STARTED -> colors.onSurfaceVariant
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .semantics { stateDescription = statusDescription }
            .size(16.dp),
    )
}
