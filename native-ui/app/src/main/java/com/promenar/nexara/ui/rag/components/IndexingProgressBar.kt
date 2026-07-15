package com.promenar.nexara.ui.rag.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class IndexingProgressAccessibility(
    val progress: Float,
    val assertive: Boolean,
)

internal fun resolveIndexingProgressAccessibility(
    progress: Float,
    isError: Boolean,
): IndexingProgressAccessibility = IndexingProgressAccessibility(
    progress = progress.coerceIn(0f, 1f),
    assertive = isError,
)

@Composable
fun IndexingProgressBar(
    progress: Float,
    statusText: String,
    subStatusText: String? = null,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val accessibility = resolveIndexingProgressAccessibility(progress, isError)
    val colors = MaterialTheme.colorScheme
    val accentColor = if (isError) colors.error else colors.primary
    val containerColor = if (isError) colors.errorContainer else colors.surfaceContainerLow
    val contentColor = if (isError) colors.onErrorContainer else colors.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = if (accessibility.assertive) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
                stateDescription = statusText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = accessibility.progress,
                    range = 0f..1f,
                )
            },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!subStatusText.isNullOrBlank()) {
                        Text(
                            text = subStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) contentColor else colors.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "${(accessibility.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = accentColor,
                )
            }

            LinearProgressIndicator(
                progress = { accessibility.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = accentColor,
                trackColor = colors.surfaceContainerHighest,
            )
        }
    }
}
