package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "紧凑步骤 深色中文", widthDp = 411, heightDp = 1050, locale = "zh-rCN")
@Composable
fun compactStepsDarkPreview() = CompactStepsPreview(dark = true)

@PreviewTest
@Preview(name = "紧凑步骤 浅色英文", widthDp = 411, heightDp = 1050, locale = "en")
@Composable
fun compactStepsLightPreview() = CompactStepsPreview(dark = false)

@PreviewTest
@Preview(name = "紧凑步骤 窄屏大字深色", widthDp = 320, heightDp = 1900, locale = "zh-rCN", fontScale = 2f)
@Composable
fun compactStepsNarrowDarkPreview() = CompactStepsPreview(dark = true)

@PreviewTest
@Preview(name = "紧凑步骤 窄屏大字浅色", widthDp = 320, heightDp = 1900, locale = "en", fontScale = 2f)
@Composable
fun compactStepsNarrowLightPreview() = CompactStepsPreview(dark = false)

@Composable
private fun CompactStepsPreview(dark: Boolean) {
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
                ThinkingTrace(
                    reasoning = "先核对本金与期限。\n正在检查3.7%年化的计算结果，并比对A方案和B方案的月供差异。",
                    isGenerating = true, fontSize = 14, elapsedMs = 3200,
                )
                ThinkingTrace("已经核对计算方法和小数精度。", false, 14, elapsedMs = 8200)
                ThinkingTrace("历史消息没有可靠耗时。", false, 14)
                InlineToolRow(
                    steps = listOf(
                        ExecutionStep("a", "tool_call", "web_search", "{\"query\":\"年化利率\"}", "one", timestamp = 1000),
                        ExecutionStep("ar", "tool_result", "web_search", toolCallId = "one", content = "找到相关资料", timestamp = 1400),
                        ExecutionStep("b", "tool_call", "calculator", "{\"rate\":3.7}", "two", timestamp = 1500),
                        ExecutionStep("br", "tool_result", "calculator", toolCallId = "two", content = "计算完成", timestamp = 2400),
                    ),
                    isExecuting = false, fontSize = 14,
                )
                RagProgressCard(
                    phases = listOf(RagPhase("vector_search", "", PhaseStatus.ACTIVE, 42)),
                    references = emptyList(), isComplete = false,
                )
                RagProgressCard(
                    phases = emptyList(), references = emptyList(), isComplete = true,
                    trace = RagRetrievalTrace(
                        outcome = RagRetrievalTrace.OUTCOME_HIT,
                        selectedCount = 3,
                        rerank = RagRetrievalTrace.RERANK_API,
                    ),
                )
                RagProgressCard(
                    phases = emptyList(), references = emptyList(), isComplete = true,
                    trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY),
                )
                RagProgressCard(
                    phases = emptyList(), references = emptyList(), isComplete = true,
                    trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED),
                )
                Spacer(Modifier.height(8.dp))
                MarkdownText(
                    markdown = """
                        ## 计算与方案
                        算一下3.7%年化、100万元、18年房贷月供，版本v2.3.1，价格¥19.99。选A还是选B、XX  新闻，A - B保持原文。

                        长段落采用舒适行高。Compare方案A与方案B时，模型提供的空格、中文标点和小数应保持稳定，便于阅读并核对计算数据。

                        ### 操作步骤
                        1. 核对本金、年限与年化利率。
                        2. 计算月供并比较两种方案。

                        ---

                        保留模型原文中的间距，也保留段落之间的呼吸空间。
                    """.trimIndent(),
                    fontSize = 14,
                )
            }
        }
    }
}
