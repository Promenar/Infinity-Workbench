package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.canvas.CanvasMetadata
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import com.promenar.nexara.ui.theme.NexaraTypography

private fun previewMetadata(title: String, id: String = "canvas-preview-dashboard") = CanvasMetadata(
    id = id,
    title = title,
    createdAt = 1_792_000_000_000L,
    updatedAt = 1_792_000_060_000L,
    currentVersion = 3,
    createdByMessageId = "message-preview",
    versions = listOf(1, 2, 3),
)

@Composable
private fun CanvasPreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.padding(NexaraSpacing.Large)) { content() }
        }
    }
}

/** 固定 Compose 图形代替网页，避免截图受到 WebView 初始化与脚本时序影响。 */
@Composable
private fun StaticChartPreview(english: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Text(
            text = if (english) "Quarterly revenue" else "季度营收",
            style = NexaraTypography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        listOf(0.52f, 0.73f, 0.92f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@PreviewTest
@Preview(name = "画布卡片 深中360", widthDp = 360, heightDp = 500, locale = "zh-rCN")
@Composable
fun canvasCardDarkChinesePreview() = CanvasPreviewFrame(dark = true) {
    CanvasCard(
        metadata = previewMetadata("季度经营看板"),
        version = 1,
        html = null,
        onOpen = {},
        previewContent = { StaticChartPreview(english = false) },
    )
}

@PreviewTest
@Preview(name = "画布卡片 浅英360 大字体", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun canvasCardLightEnglishLargeFontPreview() = CanvasPreviewFrame(dark = false) {
    CanvasCard(
        metadata = previewMetadata("Quarterly business dashboard"),
        version = 1,
        html = null,
        onOpen = {},
        previewContent = { StaticChartPreview(english = true) },
    )
}

@PreviewTest
@Preview(name = "画布列表 浅中360", widthDp = 360, heightDp = 600, locale = "zh-rCN")
@Composable
fun canvasListLightChinesePreview() = CanvasPreviewFrame(dark = false) {
    CanvasListContent(
        canvases = listOf(
            previewMetadata("季度经营看板"),
            previewMetadata("差旅费用计算器", "canvas-preview-calculator").copy(currentVersion = 1, versions = listOf(1)),
            previewMetadata("项目任务与依赖关系示意图", "canvas-preview-map"),
        ),
        onOpen = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@PreviewTest
@Preview(name = "画布列表 深英360 大字体", widthDp = 360, heightDp = 600, locale = "en", fontScale = 1.3f)
@Composable
fun canvasListDarkEnglishLargeFontPreview() = CanvasPreviewFrame(dark = true) {
    CanvasListContent(
        canvases = listOf(
            previewMetadata("Quarterly business dashboard"),
            previewMetadata("Travel expense calculator", "canvas-preview-calculator").copy(currentVersion = 1, versions = listOf(1)),
            previewMetadata("Project tasks and dependencies", "canvas-preview-map"),
        ),
        onOpen = {},
        modifier = Modifier.fillMaxSize(),
    )
}
