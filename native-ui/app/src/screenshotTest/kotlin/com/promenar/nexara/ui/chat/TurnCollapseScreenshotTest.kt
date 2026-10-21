package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.FileChangeSummary
import com.promenar.nexara.data.model.FileChangeType
import com.promenar.nexara.data.model.GenerationTimingRecord
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "完成态 收起 深色", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun turnCollapseCollapsedDarkPreview() = TurnCollapsePreviewFrame { CollapsedTurns(expanded = false) }

@PreviewTest
@Preview(name = "完成态 收起 浅色", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun turnCollapseCollapsedLightPreview() = TurnCollapsePreviewFrame(dark = false) { CollapsedTurns(expanded = false) }

@PreviewTest
@Preview(name = "完成态 收起 130%字号", widthDp = 411, heightDp = 620, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun turnCollapseCollapsedLargeFontPreview() = TurnCollapsePreviewFrame { CollapsedTurns(expanded = false) }

@PreviewTest
@Preview(name = "Collapsed English 360dp font130", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun turnCollapseCollapsedNarrowEnglishPreview() = TurnCollapsePreviewFrame { CollapsedTurns(expanded = false) }

@PreviewTest
@Preview(name = "完成态 展开 深色", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun turnCollapseExpandedDarkPreview() = TurnCollapsePreviewFrame { ToolTurn(expanded = true) }

@PreviewTest
@Preview(name = "完成态 展开 浅色", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun turnCollapseExpandedLightPreview() = TurnCollapsePreviewFrame(dark = false) { ToolTurn(expanded = true) }

@PreviewTest
@Preview(name = "完成态 展开 130%字号", widthDp = 411, heightDp = 760, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun turnCollapseExpandedLargeFontPreview() = TurnCollapsePreviewFrame { ToolTurn(expanded = true) }

@PreviewTest
@Preview(name = "出错 不收起", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun turnCollapseErrorBoundaryPreview() = TurnCollapsePreviewFrame {
    val messages = listOf(
        Message(
            "partial", MessageRole.ASSISTANT, "已读取配置，正在整理修改方案。", reasoning = "先读取配置文件。",
            executionSteps = previewCall("t1", "read_file", 1_000), createdAt = 1_760_000_000_000,
        ),
        Message("error", MessageRole.ASSISTANT, "", isError = true, errorMessage = "failed", createdAt = 1_760_000_010_000),
    )
    val groups = buildPipelineGroups(messages)
    groups.forEachIndexed { index, group ->
        PipelineBubble(
            group = group, isGenerating = false, streamingContent = "", fontSize = 15,
            collapseFinishedTurn = true, interruptedByError = groupInterruptedByError(groups, index),
        )
    }
}

@PreviewTest
@Preview(name = "待审批 不收起", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun turnCollapsePendingApprovalBoundaryPreview() = TurnCollapsePreviewFrame {
    val messages = listOf(
        Message(
            "ask", MessageRole.ASSISTANT, "需要先确认后再写入文件。", reasoning = "写入前需要用户确认。",
            executionSteps = previewCall("t1", "read_file", 1_000),
            pendingApprovalToolIds = listOf("t2"), createdAt = 1_760_000_000_000,
        ),
    )
    PipelineBubble(
        group = buildPipelineGroups(messages).single(), isGenerating = false, streamingContent = "", fontSize = 15,
        collapseFinishedTurn = true, hasPendingApproval = true,
    )
}

@Composable
private fun CollapsedTurns(expanded: Boolean) {
    ToolTurn(expanded)
    Spacer(Modifier.height(24.dp))
    val thinkingOnly = listOf(Message("thinking-only", MessageRole.ASSISTANT, "两种方案都可行，建议先采用方案一。", reasoning = "比较两个方案的成本。", createdAt = 1_760_000_060_000))
    PipelineBubble(
        group = buildPipelineGroups(thinkingOnly).single(), isGenerating = false, streamingContent = "", fontSize = 15,
        collapseFinishedTurn = true, initialProcessExpanded = expanded,
    )
}

@Composable
private fun ToolTurn(expanded: Boolean) {
    PipelineBubble(
        group = buildPipelineGroups(previewAgentTurn()).single(),
        isGenerating = false,
        streamingContent = "",
        fontSize = 15,
        modelDisplayNames = mapOf("p::model" to "Claude Sonnet 4.5"),
        collapseFinishedTurn = true,
        initialProcessExpanded = expanded,
        onCopy = {}, onRegenerate = {}, onBranch = {}, onDelete = {},
    )
}

private fun previewCall(id: String, name: String, time: Long, vararg changed: String) = listOf(
    ExecutionStep("call-$id", "tool_call", name, "{\"path\":\"app/config.kt\"}", id, timestamp = time),
    ExecutionStep(
        "result-$id", "tool_result", name, toolCallId = id, content = "执行完成", timestamp = time + 900,
        fileChanges = changed.map { FileChangeSummary(it, FileChangeType.MODIFIED, added = 3, removed = 1) }.ifEmpty { null },
    ),
)

private fun previewAgentTurn(): List<Message> = listOf(
    Message(
        "a1", MessageRole.ASSISTANT, "先查看现有配置。", reasoning = "需要先确认配置结构，再决定修改范围。",
        executionSteps = previewCall("t1", "read_file", 1_000) + previewCall("t2", "search_files", 2_000) +
            previewCall("t3", "shell_exec", 3_000),
        toolCalls = listOf(ToolCall("t1", "read_file", "{}"), ToolCall("t2", "search_files", "{}"), ToolCall("t3", "shell_exec", "{}")),
        modelId = "p::model", createdAt = 1_759_999_900_000,
        tokens = TokenUsage(timing = GenerationTimingRecord(requestStartedAt = 10_000, firstOutputAt = 11_000, endedAt = 20_000)),
    ),
    Message(
        "a2", MessageRole.ASSISTANT, "",
        executionSteps = previewCall("t4", "patch_file", 5_000, "app/config.kt") + previewCall("t5", "write_file", 6_000, "app/settings.kt"),
        toolCalls = listOf(ToolCall("t4", "patch_file", "{}"), ToolCall("t5", "write_file", "{}")),
        modelId = "p::model", createdAt = 1_759_999_950_000,
    ),
    Message(
        "final", MessageRole.ASSISTANT,
        "已完成修改：\n\n- 配置项改为按会话读取。\n- 设置页增加对应开关。\n\n运行单元测试全部通过。",
        modelId = "p::model",
        createdAt = 1_760_000_000_000,
        tokens = TokenUsage(timing = GenerationTimingRecord(requestStartedAt = 70_000, firstOutputAt = 71_000, endedAt = 82_000)),
    ),
)

@Composable
private fun TurnCollapsePreviewFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                content()
            }
        }
    }
}
