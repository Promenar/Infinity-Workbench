package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityMonospace

/** 能力图标组里的一项。 */
@Immutable
data class ModelCapabilityBadge(val icon: ImageVector, val label: String)

/**
 * 模型列表行第三行：类型标签、能力图标组、右对齐的上下文长度（如 128K）。
 * 能力只画图标，读屏把整组朗读为“能力：视觉、工具调用…”（[capabilitiesDescription]）。
 */
@Composable
fun ModelCapabilityBadges(
    typeLabel: String,
    capabilities: List<ModelCapabilityBadge>,
    contextLabel: String?,
    capabilitiesDescription: String,
    modifier: Modifier = Modifier,
    contextDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier
                .background(scheme.secondaryContainer.copy(alpha = 0.7f), BadgeShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        if (capabilities.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = capabilitiesDescription },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                capabilities.forEach { badge ->
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 20.dp)
                            .background(scheme.surfaceContainerHighest.copy(alpha = 0.7f), BadgeShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(badge.icon, contentDescription = null, Modifier.size(13.dp), scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!contextLabel.isNullOrBlank()) {
            Text(
                text = contextLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .then(
                        if (contextDescription != null) {
                            Modifier.clearAndSetSemantics { this.contentDescription = contextDescription }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

private val BadgeShape = RoundedCornerShape(5.dp)
