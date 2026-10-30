package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.common.AgentAvatar
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 旧值、新预置、生成头像与自定义色在 28 / 40 / 80dp 下的样子。 */
private val PreviewAvatars = listOf(
    Triple("✨", "#C0C1FF", "通用智能体"),
    Triple("sym:all_inclusive", "#6366F1", "通用智能体"),
    Triple("sym:terminal", "#3B82F6", "编程专家"),
    Triple("sym:ink_pen", "#10B981", "创意写作"),
    Triple("gen:", "#F59E0B", "Atlas"),
    Triple("gen:", "#D946EF", "翻译官"),
    Triple("🌐", "#64748B", "Translator"),
)

@PreviewTest
@Preview(name = "智能体头像 尺寸与预置 浅", widthDp = 360, heightDp = 260, locale = "zh-rCN")
@Composable
fun agentAvatarRampLightPreview() = AvatarPreviewSurface(InfinityThemeMode.LIGHT) { AvatarRamp() }

@PreviewTest
@Preview(name = "智能体头像 尺寸与预置 深", widthDp = 360, heightDp = 260, locale = "zh-rCN")
@Composable
fun agentAvatarRampDarkPreview() = AvatarPreviewSurface(InfinityThemeMode.DARK) { AvatarRamp() }

@PreviewTest
@Preview(name = "头像选择器 符号 浅中360", widthDp = 360, heightDp = 720, locale = "zh-rCN")
@Preview(name = "Avatar picker symbols EN 360 font130", widthDp = 360, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun agentAvatarPickerSymbolsPreview() = AvatarPreviewSurface(InfinityThemeMode.LIGHT) {
    AgentAvatarPickerContent(
        agentId = "coder",
        agentName = "编程专家",
        initialIcon = "sym:terminal",
        initialColor = "#3B82F6",
        photoUri = null,
        onConfirm = { _, _ -> },
        onDismiss = {},
        onPickPhoto = {},
    )
}

@PreviewTest
@Preview(name = "头像选择器 生成 深中360", widthDp = 360, heightDp = 720, locale = "zh-rCN")
@Composable
fun agentAvatarPickerGeneratedPreview() = AvatarPreviewSurface(InfinityThemeMode.DARK) {
    AgentAvatarPickerContent(
        agentId = "atlas",
        agentName = "Atlas",
        initialIcon = "gen:",
        initialColor = "#F59E0B",
        photoUri = null,
        onConfirm = { _, _ -> },
        onDismiss = {},
        onPickPhoto = {},
    )
}

@Composable
private fun AvatarRamp() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(28, 40, 80).forEach { size ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${size}dp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PreviewAvatars.take(if (size == 80) 3 else PreviewAvatars.size).forEachIndexed { index, (icon, color, name) ->
                    AgentAvatar(icon = icon, color = color, seed = "preview-$index", name = name, size = size.dp)
                }
            }
        }
    }
}

@Composable
private fun AvatarPreviewSurface(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}
