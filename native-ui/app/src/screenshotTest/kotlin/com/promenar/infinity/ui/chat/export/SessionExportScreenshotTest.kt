package com.promenar.infinity.ui.chat.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 导出会话面板：五个格式按钮按文字宽度排成可换行的一组（默认与 1.3 倍字号都不溢出），
// 下方是包含开关（附件只在 md、txt、html 出现）、预计输出与“保存到…”“分享”。

@PreviewTest
@Preview(name = "导出会话 Markdown 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun sessionExportMarkdownDarkPreview() = SessionExportPage(
    mode = InfinityThemeMode.DARK,
    state = SessionExportSheetState(
        estimate = SessionExportEstimateState.Ready(SessionExportEstimate(1, attachmentArchive = true, approxBytes = 2_150_000)),
    ),
)

@PreviewTest
@Preview(name = "导出会话 PDF 浅中360 大字号130", widthDp = 360, heightDp = 900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionExportPdfLargeFontPreview() = SessionExportPage(
    mode = InfinityThemeMode.LIGHT,
    state = SessionExportSheetState(
        format = SessionExportFormat.PDF,
        options = SessionExportOptions(includeThinking = true),
        estimate = SessionExportEstimateState.Ready(SessionExportEstimate(1, attachmentArchive = false, approxBytes = 1_200_000, approxPages = 14)),
    ),
)

@PreviewTest
@Preview(name = "导出会话 图片生成中 深英360 大字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun sessionExportImageRunningEnglishPreview() = SessionExportPage(
    mode = InfinityThemeMode.DARK,
    state = SessionExportSheetState(
        format = SessionExportFormat.PNG,
        options = SessionExportOptions(includeToolCalls = true),
        estimate = SessionExportEstimateState.Ready(SessionExportEstimate(3, attachmentArchive = false, approxBytes = 4_600_000)),
        running = SessionExportAction.SHARE,
    ),
)

@PreviewTest
@Preview(name = "导出会话 纯文本 浅英411", widthDp = 411, heightDp = 760, locale = "en")
@Composable
fun sessionExportTextEnglishPreview() = SessionExportPage(
    mode = InfinityThemeMode.LIGHT,
    state = SessionExportSheetState(
        format = SessionExportFormat.TXT,
        estimate = SessionExportEstimateState.Ready(SessionExportEstimate(1, attachmentArchive = false, approxBytes = 31_000)),
    ),
)

@Composable
private fun SessionExportPage(mode: InfinityThemeMode, state: SessionExportSheetState) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        // 与面板一致：左右 24dp 内边距、titleLarge 标题。
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.session_export_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                SessionExportSheetContent(
                    sessionTitle = "Linux 迁移方案讨论",
                    messageCount = 42,
                    state = state,
                    actions = SessionExportSheetActions({}, {}, {}, {}, {}),
                )
            }
        }
    }
}
