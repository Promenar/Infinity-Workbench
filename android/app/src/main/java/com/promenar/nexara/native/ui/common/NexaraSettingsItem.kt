package com.promenar.nexara.native.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

/**
 * A reusable glassmorphic item for lists, adhering to the Stitch Design Spec.
 * Used primarily in Hub, Settings, and List views.
 */
@Composable
fun NexaraSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    // Uses the standard large (16dp) radius for glass panels
    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.large)
            .clickable(onClick = onClick),
        shape = NexaraShapes.large as androidx.compose.foundation.shape.RoundedCornerShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp), // p-md
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp), // gap-md
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular icon container: w-10 h-10 bg-surface-container-high text-primary
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(NexaraColors.SurfaceHigh, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = NexaraTypography.bodyMedium.copy(fontSize = 14.sp), // text-sm
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.7f) // opacity-70
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "Navigate",
                tint = NexaraColors.Outline,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
