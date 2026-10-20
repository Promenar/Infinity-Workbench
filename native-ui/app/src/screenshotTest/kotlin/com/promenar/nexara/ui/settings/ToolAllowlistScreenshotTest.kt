package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.repository.ToolAllowlistEntry
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

/** 截图只消费展示状态，不读写白名单存储。 */
@PreviewTest
@Preview(name = "审批白名单 深色中文", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun toolAllowlistDarkPreview() = ToolAllowlistPreview(dark = true)

@PreviewTest
@Preview(name = "审批白名单 浅色英文", widthDp = 412, heightDp = 900, locale = "en")
@Composable
fun toolAllowlistLightPreview() = ToolAllowlistPreview(dark = false)

@PreviewTest
@Preview(name = "审批白名单 深色中文 字号130", widthDp = 360, heightDp = 1300, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun toolAllowlistLargeTextPreview() = ToolAllowlistPreview(dark = true)

@PreviewTest
@Preview(name = "审批白名单 深色中文 空", widthDp = 412, heightDp = 600, locale = "zh-rCN")
@Composable
fun toolAllowlistEmptyPreview() = ToolAllowlistPreview(dark = true, empty = true)

private const val PREVIEW_NOW = 1_760_000_000_000L

@Composable
private fun ToolAllowlistPreview(dark: Boolean, empty: Boolean = false) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ToolAllowlistContent(
                rows = if (empty) emptyList() else listOf(
                    ToolAllowlistRow(
                        ToolAllowlistEntry("1", "web_fetch", """["host","developer.android.com"]""", null, PREVIEW_NOW - 5 * 60_000, "s1"),
                        AllowlistScopeLabel.Global,
                    ),
                    ToolAllowlistRow(
                        ToolAllowlistEntry("2", "shell_exec", """["process","builtin","./gradlew :app:testSideloadDebugUnitTest"]""", null, PREVIEW_NOW - 3 * 3_600_000, "s1"),
                        AllowlistScopeLabel.Global,
                    ),
                    ToolAllowlistRow(
                        ToolAllowlistEntry("3", "write_file", """["path","/docs/plan.md"]""", "workspace:w1", PREVIEW_NOW - 26 * 3_600_000, "s2"),
                        AllowlistScopeLabel.Workspace("Nexara"),
                    ),
                    ToolAllowlistRow(
                        ToolAllowlistEntry("4", "create_file", """["path","/notes/todo.md"]""", "session:s3", PREVIEW_NOW - 3 * 86_400_000, "s3"),
                        AllowlistScopeLabel.Session,
                    ),
                ),
                onRemove = {},
                onClear = {},
                modifier = Modifier.padding(horizontal = NexaraSpacing.ScreenHorizontal),
                now = PREVIEW_NOW,
            )
        }
    }
}
