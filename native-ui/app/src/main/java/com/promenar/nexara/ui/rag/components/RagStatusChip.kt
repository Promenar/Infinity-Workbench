package com.promenar.nexara.ui.rag.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class RagStatus {
    READY,
    INDEXING,
    ERROR,
    PENDING
}

private val RagStatus.color: Color
    get() = when (this) {
        RagStatus.READY -> NexaraColors.RagReady
        RagStatus.INDEXING -> NexaraColors.RagIndexing
        RagStatus.ERROR -> NexaraColors.RagError
        RagStatus.PENDING -> NexaraColors.RagPending
    }

@Composable
fun RagStatusChip(
    status: RagStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(status.color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(status.color)
        )
        Text(
            text = when (status) {
                RagStatus.READY -> stringResource(R.string.rag_status_ready)
                RagStatus.INDEXING -> stringResource(R.string.rag_status_indexing)
                RagStatus.ERROR -> stringResource(R.string.rag_status_error)
                RagStatus.PENDING -> stringResource(R.string.rag_status_pending)
            },
            style = NexaraTypography.labelMedium,
            color = status.color
        )
    }
}
