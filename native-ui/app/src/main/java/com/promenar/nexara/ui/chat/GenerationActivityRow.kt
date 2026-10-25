package com.promenar.nexara.ui.chat

import com.promenar.nexara.ui.common.LocalNexaraHaptics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.IconToggleButton
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMotion

/** 浮岛上的亮屏开关：只影响本次生成，默认值来自“生成时保持亮屏”设置。 */
@Composable
internal fun KeepScreenOnToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalNexaraHaptics.current
    val state = stringResource(if (on) R.string.keep_screen_toggle_on else R.string.keep_screen_toggle_off)
    IconToggleButton(
        checked = on,
        onCheckedChange = {
            haptics.toggle(it)
            onToggle()
        },
        modifier = modifier
            .testTag(UiTags.CHAT_KEEP_SCREEN_TOGGLE)
            .semantics { stateDescription = state },
    ) {
        Icon(
            imageVector = if (on) Icons.Rounded.LightMode else Icons.Outlined.LightMode,
            contentDescription = stringResource(R.string.keep_screen_toggle_cd),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp),
        )
    }
}
