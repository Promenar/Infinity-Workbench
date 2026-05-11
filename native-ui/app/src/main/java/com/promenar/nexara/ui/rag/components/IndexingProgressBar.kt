package com.promenar.nexara.ui.rag.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun IndexingProgressBar(
    progress: Float,
    statusText: String? = null,
    subStatusText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.6f))
            .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = statusText ?: stringResource(R.string.rag_indexing_progress),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.OnSurface
                )
                if (!subStatusText.isNullOrBlank()) {
                    Text(
                        text = subStatusText,
                        style = NexaraTypography.bodySmall.copy(fontSize = 11.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
            Text(
                text = "${(progress * 100).toInt()}%",
                style = NexaraTypography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = NexaraColors.Primary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(NexaraColors.SurfaceHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(NexaraColors.Primary)
            )
        }
    }
}
