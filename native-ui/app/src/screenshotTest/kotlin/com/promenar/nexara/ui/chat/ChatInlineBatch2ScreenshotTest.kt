package com.promenar.nexara.ui.chat

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
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "单项审批 深中411", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable
fun approvalSingleBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "write_file",
        description = "此操作会修改目标文件，请核对内容后批准。",
        calls = listOf(previewWriteCall()),
        onAlwaysAllow = {},
    )
}

@PreviewTest
@Preview(name = "多项审批 深中411", widthDp = 411, heightDp = 1120, locale = "zh-rCN")
@Composable
fun approvalMultipleBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "write_file",
        description = "逐项核对目标和内容，也可批准列表中的全部调用。",
        calls = listOf(
            previewWriteCall(),
            ApprovalDisplayCall(
                name = "delete_file",
                argumentsSummary = "{\"path\":\"/notes/archive.md\"}",
                riskLabel = "删除文件",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.DELETE, "/notes/archive.md", null),
                executionTarget = "[\"path\",\"/notes/archive.md\"]",
                highRisk = true,
            ),
        ),
        onApproveAll = {},
        onAlwaysAllow = {},
    )
}

@PreviewTest
@Preview(name = "内置命令审批 深中411", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun approvalBuiltinShellBatch2Preview() = InlineBatch2Frame {
    val command = "cd notes\nls -la\ngrep -rn \"TODO\" ."
    ApprovalCard(
        toolName = "shell_exec",
        description = "",
        calls = listOf(
            ApprovalDisplayCall(
                name = "shell_exec",
                argumentsSummary = "{\"command\":\"cd notes\"}",
                riskLabel = "命令执行",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", command, commandRunsInWorkspace = true),
                executionTarget = "[\"process\",\"builtin\",\"cd notes\\nls -la\\ngrep -rn \\\"TODO\\\" .\"]",
                highRisk = true,
            ),
        ),
        onAlwaysAllow = {},
    )
}

@PreviewTest
@Preview(name = "已执行审批 深中411", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun approvalExecutedBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "write_file",
        description = "",
        calls = listOf(previewWriteCall()),
        isExecuted = true,
        executionTime = "10:24",
    )
}

@PreviewTest
@Preview(name = "检索完成 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable
fun retrievalCompletedBatch2Preview() = InlineBatch2Frame {
    RagProgressCard(
        phases = emptyList(),
        references = (1..5).map { index ->
            RagReference("ref-$index", "/notes/guide-$index.md", "知识库检索内容 $index", 0.9f)
        },
        isComplete = true,
        trace = RagRetrievalTrace(selectedCount = 5),
    )
}

@PreviewTest
@Preview(name = "检索失败 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable
fun retrievalFailedBatch2Preview() = InlineBatch2Frame {
    RagProgressCard(
        phases = emptyList(),
        references = emptyList(),
        isComplete = true,
        trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED),
    )
}

@PreviewTest
@Preview(name = "检索进行中 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable
fun retrievalRunningBatch2Preview() = InlineBatch2Frame {
    RagProgressCard(
        phases = listOf(RagPhase("vector_search", "vector_search", PhaseStatus.ACTIVE, 42)),
        references = emptyList(),
        isComplete = false,
    )
}

@PreviewTest
@Preview(name = "知识库部分来源失败 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable
fun retrievalPartialKnowledgeBatch2Preview() = InlineBatch2Frame {
    RagProgressCard(
        phases = emptyList(),
        references = emptyList(),
        kgPaths = (1..2).map { index ->
            val source = KgNode("source-$index", "项目约束 $index", "concept")
            val target = KgNode("target-$index", "验收要求 $index", "concept")
            KgPath(
                queryKeywords = listOf("项目", "验收"),
                nodes = listOf(source, target),
                edges = listOf(KgEdge(source.id, target.id, "适用", 1.0)),
                reasoning = "项目约束关联到对应验收要求。",
            )
        },
        isComplete = true,
        trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED),
    )
}

@PreviewTest
@Preview(name = "网页部分来源失败 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable
fun retrievalPartialWebBatch2Preview() = InlineBatch2Frame {
    RagProgressCard(
        phases = emptyList(),
        references = emptyList(),
        citations = (1..3).map { index ->
            Citation(
                title = "项目资料 $index",
                url = "https://example.com/guide/$index",
                source = "项目文档",
                snippet = "项目资料摘要 $index",
            )
        },
        isComplete = true,
        trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED),
    )
}

@PreviewTest
@Preview(name = "单项审批 大字深中411", widthDp = 411, heightDp = 820, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun approvalLargeFontBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "write_file",
        description = "此操作会修改目标文件，请核对内容后批准。",
        calls = listOf(previewWriteCall()),
        onAlwaysAllow = {},
    )
}

private fun previewWriteCall() = ApprovalDisplayCall(
    name = "write_file",
    argumentsSummary = "{\"path\":\"/notes/meeting.md\"}",
    riskLabel = "文件写入",
    preview = ApprovalPreview.Preview(
        ApprovalPreview.Action.OVERWRITE,
        "/notes/meeting.md",
        """
            # 项目周会
            日期：2026-10-18
            参与人：产品与研发

            ## 待办事项
            1. 核对本周交付内容。
            2. 补充验收记录。
            3. 确认下周计划。
        """.trimIndent(),
    ),
    executionTarget = "[\"path\",\"/notes/meeting.md\"]",
)

@Composable
private fun InlineBatch2Frame(content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = NexaraThemeMode.DARK,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                content()
            }
        }
    }
}

@PreviewTest
@Preview(name = "Approval current English 360dp font130", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun approvalMultipleNarrowEnglishBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "write_file", description = "Update workspace",
        calls = listOf(
            ApprovalDisplayCall("write_file", "{\"path\":\"notes.md\"}", "File write"),
            ApprovalDisplayCall("delete_file", "{\"path\":\"old.md\"}", "Delete"),
        ),
        onApproveAll = {},
    )
}

/** 约 80 行的 python_exec 代码：每行带行号，便于在截图中确认首尾都能看到。 */
private val LongPythonCode = buildString {
    appendLine("import csv")
    appendLine("from pathlib import Path")
    for (index in 3..79) {
        appendLine("rows.append({\"line\": $index, \"value\": compute($index)})  # L$index")
    }
    append("print(\"done: L80\")")
}

@PreviewTest
@Preview(name = "长代码审批收起 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun approvalLongPythonCollapsedBatch2Preview() = InlineBatch2Frame {
    ApprovalCard(
        toolName = "python_exec",
        description = "",
        calls = listOf(
            ApprovalDisplayCall(
                name = "python_exec",
                argumentsSummary = "{\"code\":\"import csv\"}",
                riskLabel = "命令执行",
                preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", LongPythonCode, commandRunsInWorkspace = true),
                highRisk = true,
            ),
        ),
        onAlwaysAllow = {},
    )
}

@PreviewTest
@Preview(name = "长代码审批展开顶部 深中411", widthDp = 411, heightDp = 460, locale = "zh-rCN")
@Composable
fun approvalLongPythonExpandedTopBatch2Preview() = InlineBatch2Frame {
    ApprovalTextBlock(LongPythonCode, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "长代码审批展开底部 深中411", widthDp = 411, heightDp = 460, locale = "zh-rCN")
@Composable
fun approvalLongPythonExpandedBottomBatch2Preview() = InlineBatch2Frame {
    // 滚动值超出范围时由滚动容器收敛到末尾，用于确认最后一行可见。
    ApprovalTextBlock(LongPythonCode, initiallyExpanded = true, initialScroll = Int.MAX_VALUE)
}
