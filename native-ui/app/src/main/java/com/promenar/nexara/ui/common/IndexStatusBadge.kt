package com.promenar.nexara.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class FileIndexStatus {
    INDEXED,
    INDEXING,
    STALE,
    NOT_INDEXED,
    FAILED
}

private val FileIndexStatus.color: Color
    get() = when (this) {
        FileIndexStatus.INDEXED -> NexaraColors.RagReady
        FileIndexStatus.INDEXING -> NexaraColors.RagIndexing
        FileIndexStatus.STALE -> NexaraColors.StatusWarning
        FileIndexStatus.NOT_INDEXED -> NexaraColors.RagPending
        FileIndexStatus.FAILED -> NexaraColors.RagError
    }

private val FileIndexStatus.label: String
    get() = when (this) {
        FileIndexStatus.INDEXED -> "已索引"
        FileIndexStatus.INDEXING -> "索引中"
        FileIndexStatus.STALE -> "过时"
        FileIndexStatus.NOT_INDEXED -> "未索引"
        FileIndexStatus.FAILED -> "失败"
    }

@Composable
fun IndexStatusBadge(
    status: FileIndexStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(status.color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == FileIndexStatus.INDEXING) {
            PulseDot(color = status.color)
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(status.color)
            )
        }
        Text(
            text = status.label,
            style = NexaraTypography.labelSmall,
            color = status.color
        )
    }
}

@Composable
private fun PulseDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
    )
}
