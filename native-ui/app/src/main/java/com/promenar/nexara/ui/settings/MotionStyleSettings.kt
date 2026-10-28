package com.promenar.nexara.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.chat.ComposerIslandCorner
import com.promenar.nexara.ui.chat.ComposerIslandLights
import com.promenar.nexara.ui.chat.LedEmptyChat
import com.promenar.nexara.ui.chat.ParticleWordmark
import com.promenar.nexara.ui.chat.composerIslandGlow
import com.promenar.nexara.ui.chat.composerIslandMaterial
import com.promenar.nexara.ui.chat.rememberComposerGlowState
import com.promenar.nexara.ui.common.motion.GenerationMotionPhase
import com.promenar.nexara.ui.common.motion.LocalGenerationMotion
import com.promenar.nexara.ui.common.motion.rememberDemoGenerationMotion
import com.promenar.nexara.ui.common.GenerationDots
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraPopoverSpec
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.nexaraPopoverAnchor
import com.promenar.nexara.ui.common.rememberNexaraPopoverAnchor
import com.promenar.nexara.ui.theme.ComposerGlowStyle
import com.promenar.nexara.ui.theme.EmptyChatStyle
import com.promenar.nexara.ui.theme.GenerationIndicatorStyle
import com.promenar.nexara.ui.theme.LocalNexaraMotionStyles
import com.promenar.nexara.ui.theme.NexaraMotionStyles

/** 一个可选样式的名称与一句话说明。 */
internal data class MotionStyleLabel(@StringRes val name: Int, @StringRes val description: Int)

internal fun ComposerGlowStyle.label(): MotionStyleLabel = when (this) {
    ComposerGlowStyle.AURORA -> MotionStyleLabel(R.string.motion_composer_aurora, R.string.motion_composer_aurora_desc)
    ComposerGlowStyle.BREATHE -> MotionStyleLabel(R.string.motion_composer_breathe, R.string.motion_composer_breathe_desc)
    ComposerGlowStyle.MESH -> MotionStyleLabel(R.string.motion_composer_mesh, R.string.motion_composer_mesh_desc)
    ComposerGlowStyle.BOTTOM_STRIP -> MotionStyleLabel(R.string.motion_composer_bottom_strip, R.string.motion_composer_bottom_strip_desc)
    ComposerGlowStyle.SCOPE_SCREEN -> MotionStyleLabel(R.string.motion_composer_scope_screen, R.string.motion_composer_scope_screen_desc)
    ComposerGlowStyle.SWEEP_GRID -> MotionStyleLabel(R.string.motion_composer_sweep_grid, R.string.motion_composer_sweep_grid_desc)
}

internal fun GenerationIndicatorStyle.label(): MotionStyleLabel = when (this) {
    GenerationIndicatorStyle.WAVE -> MotionStyleLabel(R.string.motion_indicator_wave, R.string.motion_indicator_wave_desc)
    GenerationIndicatorStyle.PORTS -> MotionStyleLabel(R.string.motion_indicator_ports, R.string.motion_indicator_ports_desc)
    GenerationIndicatorStyle.MATRIX -> MotionStyleLabel(R.string.motion_indicator_matrix, R.string.motion_indicator_matrix_desc)
    GenerationIndicatorStyle.STACKED -> MotionStyleLabel(R.string.motion_indicator_stacked, R.string.motion_indicator_stacked_desc)
    GenerationIndicatorStyle.BAYS -> MotionStyleLabel(R.string.motion_indicator_bays, R.string.motion_indicator_bays_desc)
    GenerationIndicatorStyle.INFINITY_LINE -> MotionStyleLabel(R.string.motion_indicator_infinity_line, R.string.motion_indicator_infinity_line_desc)
    GenerationIndicatorStyle.INFINITY_LED -> MotionStyleLabel(R.string.motion_indicator_infinity_led, R.string.motion_indicator_infinity_led_desc)
}

internal fun EmptyChatStyle.label(): MotionStyleLabel = when (this) {
    EmptyChatStyle.FLOW -> MotionStyleLabel(R.string.motion_empty_flow, R.string.motion_empty_flow_desc)
    EmptyChatStyle.LED_WORDMARK -> MotionStyleLabel(R.string.motion_empty_led_wordmark, R.string.motion_empty_led_wordmark_desc)
    EmptyChatStyle.MOBIUS -> MotionStyleLabel(R.string.motion_empty_mobius, R.string.motion_empty_mobius_desc)
    EmptyChatStyle.MOBIUS_LED -> MotionStyleLabel(R.string.motion_empty_mobius_led, R.string.motion_empty_mobius_led_desc)
    EmptyChatStyle.MORPH -> MotionStyleLabel(R.string.motion_empty_morph, R.string.motion_empty_morph_desc)
    EmptyChatStyle.LED_GLOBE -> MotionStyleLabel(R.string.motion_empty_led_globe, R.string.motion_empty_led_globe_desc)
}

/** 外观页“生成动效”分组：三行分别打开带实时预览的样式选择面板。 */
@Composable
internal fun MotionStyleRows(
    motion: NexaraMotionStyles,
    onComposerSelect: (ComposerGlowStyle) -> Unit,
    onIndicatorSelect: (GenerationIndicatorStyle) -> Unit,
    onEmptyChatSelect: (EmptyChatStyle) -> Unit,
) {
    MotionStylePicker(
        title = stringResource(R.string.motion_composer_title),
        options = ComposerGlowStyle.entries,
        selected = motion.composer,
        label = { it.label() },
        tag = "theme_motion_composer",
        onSelect = onComposerSelect,
        phaseDemo = true,
    ) { ComposerPreview(it) }
    MotionStylePicker(
        title = stringResource(R.string.motion_indicator_title),
        options = GenerationIndicatorStyle.entries,
        selected = motion.indicator,
        label = { it.label() },
        tag = "theme_motion_indicator",
        onSelect = onIndicatorSelect,
        phaseDemo = true,
    ) { IndicatorPreview(it) }
    MotionStylePicker(
        title = stringResource(R.string.motion_empty_chat_title),
        options = EmptyChatStyle.entries,
        selected = motion.emptyChat,
        label = { it.label() },
        tag = "theme_motion_empty_chat",
        onSelect = onEmptyChatSelect,
    ) { EmptyChatPreview(it) }
}

@Composable
private fun <T : Enum<T>> MotionStylePicker(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> MotionStyleLabel,
    tag: String,
    onSelect: (T) -> Unit,
    /** 预览按演示脚本轮播各生成阶段，面板顶部显示当前阶段。 */
    phaseDemo: Boolean = false,
    preview: @Composable (T) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val anchor = rememberNexaraPopoverAnchor()
    val haptics = LocalNexaraHaptics.current
    NexaraValueRow(
        title = title,
        value = stringResource(label(selected).name),
        modifier = Modifier.nexaraPopoverAnchor(anchor).testTag(tag),
        onClick = { if (!anchor.consumeRecentDismiss()) show = true },
    )
    NexaraBottomSheet(show = show, title = title, onDismiss = { show = false }, scrollContent = true, popover = NexaraPopoverSpec(anchor)) {
        val demo = if (phaseDemo) rememberDemoGenerationMotion() else LocalGenerationMotion.current
        CompositionLocalProvider(LocalGenerationMotion provides demo) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phaseDemo) {
                Text(
                    stringResource(R.string.motion_preview_phase, stringResource((demo.displayed ?: GenerationMotionPhase.STREAM).labelRes())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("${tag}_phase"),
                )
            }
            options.forEach { option ->
                val isSelected = option == selected
                val text = label(option)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            haptics.select(isSelected)
                            onSelect(option)
                            show = false
                        }
                        .testTag("${tag}_${option.name.lowercase()}"),
                ) {
                    Column {
                        preview(option)
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(text.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(text.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = isSelected, onClick = null)
                        }
                    }
                }
            }
        }
        }
    }
}

/** 生成阶段在设置预览中的名称。 */
internal fun GenerationMotionPhase.labelRes(): Int = when (this) {
    GenerationMotionPhase.WAIT -> R.string.motion_phase_wait
    GenerationMotionPhase.STREAM -> R.string.motion_phase_stream
    GenerationMotionPhase.TOOL -> R.string.motion_phase_tool
    GenerationMotionPhase.APPROVAL -> R.string.motion_phase_approval
    GenerationMotionPhase.RETRY -> R.string.motion_phase_retry
}

/** 浮岛预览：一个处于生成中的迷你浮岛，右下角画出停止键的位置。 */
@Composable
private fun ComposerPreview(style: ComposerGlowStyle) {
    CompositionLocalProvider(LocalNexaraMotionStyles provides LocalNexaraMotionStyles.current.copy(composer = style)) {
        val glow = rememberComposerGlowState(generating = true, error = false)
        val material = composerIslandMaterial()
        Box(Modifier.fillMaxWidth().height(124.dp).clipToBounds().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(88.dp).composerIslandGlow(glow, material.topHighlight),
                shape = RoundedCornerShape(ComposerIslandCorner),
                color = material.container,
                border = material.border,
            ) {
                Box {
                    ComposerIslandLights(glow, style, Modifier.matchParentSize())
                    Text(
                        stringResource(R.string.motion_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp),
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp).size(40.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surface))
                    }
                }
            }
        }
    }
}

/** 指示预览：左边放大两倍看清细节，右边是实际大小。 */
@Composable
private fun IndicatorPreview(style: GenerationIndicatorStyle) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GenerationDots(dotSize = 12.dp, gap = 10.dp, lift = 8.dp, style = style)
        }
        Box(Modifier.size(width = 72.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            GenerationDots(style = style)
        }
    }
}

/** 空会话预览：缩小的画布与较少的粒子。 */
@Composable
private fun EmptyChatPreview(style: EmptyChatStyle) {
    Box(
        Modifier.fillMaxWidth().height(156.dp).padding(8.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (style == EmptyChatStyle.FLOW) {
            ParticleWordmark(dispersed = false, modifier = Modifier.fillMaxSize(), centerFraction = 0.5f, maxTextSizeDp = 36f, logoLimit = 380, ambientCount = 60)
        } else {
            LedEmptyChat(style = style, dispersed = false, modifier = Modifier.fillMaxSize(), centerFraction = 0.5f, particleScale = 0.35f)
        }
    }
}
