package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.settings.DefaultModelRole
import com.promenar.nexara.ui.settings.DefaultModelSettingItem
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "上下文管理 浅色", widthDp = 412, heightDp = 650, locale = "en")
@Preview(name = "上下文管理 浅色 大字号130", widthDp = 412, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun sessionContextManagementLightPreview() = SessionContextManagementPreview(dark = false)

@PreviewTest
@Preview(name = "上下文管理 深色", widthDp = 412, heightDp = 650, locale = "zh-rCN")
@Preview(name = "上下文管理 深色 大字号130", widthDp = 412, heightDp = 860, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionContextManagementDarkPreview() = SessionContextManagementPreview(dark = true)

@PreviewTest
@Preview(name = "上下文用量 浅色", widthDp = 412, heightDp = 540, locale = "en")
@Preview(name = "上下文用量 浅色 大字号130", widthDp = 412, heightDp = 680, locale = "en", fontScale = 1.3f)
@Composable
fun contextUsageLightPreview() = ContextUsagePreview(dark = false)

@PreviewTest
@Preview(name = "上下文用量 深色", widthDp = 412, heightDp = 540, locale = "zh-rCN")
@Preview(name = "上下文用量 深色 大字号130", widthDp = 412, heightDp = 680, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun contextUsageDarkPreview() = ContextUsagePreview(dark = true)

@PreviewTest
@Preview(name = "快速模型 浅色", widthDp = 412, heightDp = 440, locale = "en")
@Preview(name = "快速模型 浅色 大字号130", widthDp = 412, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun quickModelLightPreview() = QuickModelPreview(dark = false)

@PreviewTest
@Preview(name = "快速模型 深色", widthDp = 412, heightDp = 440, locale = "zh-rCN")
@Preview(name = "快速模型 深色 大字号130", widthDp = 412, heightDp = 560, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun quickModelDarkPreview() = QuickModelPreview(dark = true)

@Composable
private fun ContextCompactionTheme(dark: Boolean, content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun SessionContextManagementPreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = NexaraSpacing.XLarge, vertical = NexaraSpacing.Large),
    ) {
        SessionContextSettingsSection(
            autoCompactionThreshold = 0.8,
            keepRecentTurns = 5,
            isGenerating = false,
            isCompressing = false,
            onThresholdChange = {},
            onKeepRecentTurnsChange = {},
            onCompactNow = {},
        )
    }
}

@Composable
private fun ContextUsagePreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Box(Modifier.fillMaxSize().padding(NexaraSpacing.Large), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.width(300.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            ContextUsagePanel(
                state = ChatViewModel.TokenIndicatorState(
                    used = 91_300,
                    max = 128_000,
                    systemTokens = 3_200,
                    summaryTokens = 8_500,
                    activeTokens = 75_300,
                    ragTokens = 4_300,
                    foldedToolResults = 7,
                    autoCompactionThreshold = 0.8,
                    keepRecentTurns = 5,
                ),
                onManualSummary = {},
            )
        }
    }
}

@Composable
private fun QuickModelPreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Column(Modifier.fillMaxSize().padding(NexaraSpacing.Large)) {
        NexaraSettingsSection(title = stringResource(R.string.settings_section_ai_models)) {
            DefaultModelSettingItem(
                role = DefaultModelRole.SUMMARY,
                selectedModelName = "Example Chat Model",
                onSelectModel = {},
                onClearModel = {},
            )
        }
    }
}
