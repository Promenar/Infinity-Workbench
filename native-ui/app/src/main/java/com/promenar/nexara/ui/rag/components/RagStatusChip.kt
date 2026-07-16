package com.promenar.nexara.ui.rag.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R

enum class RagStatus {
    READY,
    INDEXING,
    ERROR,
    PENDING
}

@Composable
fun RagStatusChip(
    status: RagStatus,
    modifier: Modifier = Modifier
) {
    val label = stringResource(
        when (status) {
            RagStatus.READY -> R.string.rag_status_ready
            RagStatus.INDEXING -> R.string.rag_status_indexing
            RagStatus.ERROR -> R.string.rag_status_error
            RagStatus.PENDING -> R.string.rag_status_pending
        },
    )
    val containerColor: Color
    val contentColor: Color
    when (status) {
        RagStatus.READY -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        RagStatus.INDEXING -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        RagStatus.ERROR -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
        RagStatus.PENDING -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Surface(
        modifier = modifier.clearAndSetSemantics { stateDescription = label },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(contentColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
