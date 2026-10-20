package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "工具失败详情 深色中文", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun toolErrorBlockDarkPreview() = ToolErrorBlockPreview(dark = true)

@PreviewTest
@Preview(name = "工具失败详情 浅色英文", widthDp = 411, heightDp = 1100, locale = "en")
@Composable
fun toolErrorBlockLightPreview() = ToolErrorBlockPreview(dark = false)

@PreviewTest
@Preview(name = "工具失败详情 窄屏大字深色", widthDp = 320, heightDp = 1700, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun toolErrorBlockLargeFontPreview() = ToolErrorBlockPreview(dark = true)

@Composable
private fun ToolErrorBlockPreview(dark: Boolean) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(ChatStepSpacing),
            ) {
                InlineToolRow(
                    steps = listOf(
                        ExecutionStep("g", "tool_call", "generate_image", "{\"prompt\":\"Simple flat illustration of a red apple on a white table.\"}", "img", timestamp = 1_000),
                        ExecutionStep(
                            "gr", "error", "generate_image", toolCallId = "img", timestamp = 1_529,
                            content = "工具执行失败：Image generation failed (HTTP 503 · model_not_found): No available channel for model " +
                                "default::sensenova-u1.5-lite under group default (distributor) (request id: 20261020145110140000000000000000)",
                        ),
                    ),
                    isExecuting = false, fontSize = 14, initiallyExpanded = true,
                )
                InlineToolRow(
                    steps = listOf(
                        ExecutionStep("s", "tool_call", "shell_exec", "{\"command\":\"ls /media/missing\"}", "sh", timestamp = 2_000),
                        ExecutionStep(
                            "sr", "error", "shell_exec", toolCallId = "sh", timestamp = 2_300,
                            content = "工具执行失败：{\"error_code\":\"COMMAND_FAILED\",\"guidance\":\"命令以非零状态退出，请检查路径后重试。\"," +
                                "\"exit_code\":2,\"stderr\":\"ls: /media/missing: No such file or directory\"}",
                        ),
                    ),
                    isExecuting = false, fontSize = 14, initiallyExpanded = true,
                )
                InlineToolRow(
                    steps = listOf(
                        ExecutionStep("l", "tool_call", "generate_image", "{\"prompt\":\"apple\"}", "old", timestamp = 3_000),
                        ExecutionStep(
                            "lr", "error", "generate_image", toolCallId = "old", timestamp = 3_400,
                            content = "工具执行失败：Image generation failed: Image generation failed (503): {\"error\":{\"code\":\"model_not_found\"," +
                                "\"message\":\"No available channel for model default::sensenova-u1.5-lite under group default (distributor) (request id: 2026102014511014",
                        ),
                    ),
                    isExecuting = false, fontSize = 14, initiallyExpanded = true,
                )
            }
        }
    }
}
