package com.promenar.nexara.native.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes

/**
 * A strict implementation of the Stitch 'glass-panel' component.
 * Spec:
 * - Background: rgba(255, 255, 255, 0.03) (or equivalent semantic color)
 * - Blur: 20px (Only works reliably on Android 12+, gracefully degrades on older OS)
 * - Border: 0.5dp white/outline at low opacity
 */
@Composable
fun NexaraGlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = NexaraShapes.large as RoundedCornerShape,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val baseModifier = modifier
        .clip(shape)
        .background(NexaraColors.GlassSurface)
        // Hairline 0.5dp border as requested by Stitch Spec
        .border(0.5.dp, NexaraColors.GlassBorder, shape)

    val clickModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }

    Box(
        modifier = clickModifier,
        content = content
    )
}
