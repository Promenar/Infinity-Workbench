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
import com.promenar.nexara.data.subagent.SubagentApproval
import com.promenar.nexara.data.subagent.SubagentBatch
import com.promenar.nexara.data.subagent.SubagentEvent
import com.promenar.nexara.data.subagent.SubagentRun
import com.promenar.nexara.data.subagent.SubagentRunner
import com.promenar.nexara.data.subagent.SubagentStatus
import com.promenar.nexara.data.subagent.SubagentToolProfile
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "子代理分组卡 三种状态 深色", widthDp = 411, heightDp = 460, locale = "zh-rCN")
@Composable
fun subagentGroupCardDarkPreview() = SubagentFrame {
    SubagentGroupCardContent(groupBatch(zh = true), onOpen = {}, now = NOW)
}

@PreviewTest
@Preview(name = "Subagent group card English 360dp font130", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Composable
fun subagentGroupCardNarrowEnglishPreview() = SubagentFrame(dark = false) {
    SubagentGroupCardContent(groupBatch(zh = false), onOpen = {}, now = NOW)
}

@PreviewTest
@Preview(name = "子代理查看页 深色", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Composable
fun subagentViewerDarkPreview() = SubagentFrame {
    Column(modifier = Modifier.weight(1f)) {
        SubagentViewerContent(viewerRun(), modelName = "DeepSeek V3.2", now = NOW)
    }
    SubagentViewerFooter()
}

@PreviewTest
@Preview(name = "Subagent viewer failed English 360dp font130", widthDp = 360, heightDp = 1500, locale = "en", fontScale = 1.3f)
@Composable
fun subagentViewerFailedEnglishPreview() = SubagentFrame(dark = false) {
    Column(modifier = Modifier.weight(1f)) {
        SubagentViewerContent(
            viewerRun().copy(
                description = "Check build scripts",
                prompt = "Run the dependency report for the app module and list duplicated libraries.",
                toolProfile = SubagentToolProfile.INHERIT,
                events = listOf(
                    SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"command\":\"./gradlew :app:dependencies\"}", toolName = "shell_exec", at = NOW - 30_000),
                    SubagentEvent(SubagentEvent.Kind.APPROVAL, text = "shell_exec", toolName = "shell_exec", at = NOW - 29_000),
                    SubagentEvent(SubagentEvent.Kind.ERROR, text = "The approval was not handled within 10 minutes; the subagent stopped.", failed = true, at = NOW - 2_000),
                ),
                status = SubagentStatus.FAILED,
                result = null,
                error = "The approval was not handled within 10 minutes; the subagent stopped.",
            ),
            modelName = "DeepSeek V3.2",
            now = NOW,
        )
    }
    SubagentViewerFooter()
}

@PreviewTest
@Preview(name = "子代理审批 深色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun subagentApprovalDarkPreview() = SubagentFrame {
    SubagentApprovalContent(approval(), sessionTitle = "构建排查", remember = true, onRememberChange = {})
}

@PreviewTest
@Preview(name = "Subagent approval no target English font130", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun subagentApprovalNoTargetEnglishPreview() = SubagentFrame(dark = false) {
    SubagentApprovalContent(
        approval().copy(subagentPreset = null, subagentDescription = "Write a summary file", toolName = "write_file", risk = ToolRisk.FILE_WRITE, target = null, preview = "notes/summary.md"),
        sessionTitle = null,
        remember = false,
        onRememberChange = {},
    )
}

private const val NOW = 1_000_000L

private fun groupBatch(zh: Boolean) = SubagentBatch(
    sessionId = "session",
    stepId = "step",
    runs = listOf(
        run(0, SubagentStatus.RUNNING, if (zh) "梳理网络层调用链" else "Trace the network call chain").copy(step = 3, currentTool = "read_file"),
        run(1, SubagentStatus.COMPLETED, if (zh) "统计测试覆盖" else "Count test coverage").copy(
            endedAt = NOW - 4_000,
            result = if (zh) "## 结论\n共 412 个单元测试，覆盖数据层与生成运行时。" else "## Findings\n412 unit tests cover the data layer and generation runtime.",
        ),
        run(2, SubagentStatus.FAILED, if (zh) "检查构建脚本" else "Check build scripts").copy(
            endedAt = NOW - 2_000,
            error = if (zh) SubagentRunner.APPROVAL_TIMEOUT else "The approval was not handled within 10 minutes; the subagent stopped.",
        ),
    ),
)

private fun viewerRun() = run(0, SubagentStatus.COMPLETED, "梳理网络层调用链").copy(
    prompt = "阅读 data/remote 目录，说明一次流式请求从发起到解析的调用链，列出关键类。",
    endedAt = NOW - 1_000,
    step = 4,
    inputTokens = 18_240,
    outputTokens = 1_320,
    totalTokens = 19_560,
    events = listOf(
        SubagentEvent(SubagentEvent.Kind.TEXT, text = "先列出 data/remote 目录结构。", at = NOW - 40_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"path\":\"data/remote\"}", toolName = "list_files", at = NOW - 39_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_RESULT, text = "middleware/\nprotocol/\nprovider/\nStreamConfig.kt", toolName = "list_files", at = NOW - 38_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"path\":\"data/remote/provider/OpenAiProvider.kt\"}", toolName = "read_file", at = NOW - 30_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_RESULT, text = "文件不存在", toolName = "read_file", failed = true, at = NOW - 29_000),
    ),
    result = "## 调用链\n1. `StreamTextParams` 进入中间件链\n2. Provider 发起 SSE 请求\n3. `StreamChunk` 逐块解析后交回运行时",
)

private fun run(index: Int, status: SubagentStatus, description: String) = SubagentRun(
    index = index,
    preset = if (index == 0) "explorer" else null,
    description = description,
    prompt = description,
    toolProfile = if (index == 0) SubagentToolProfile.READ_ONLY else SubagentToolProfile.INHERIT,
    modelId = "deepseek::deepseek-chat",
    maxSteps = 12,
    status = status,
    startedAt = NOW - 42_000,
)

private fun approval() = SubagentApproval(
    id = "a1",
    sessionId = "session",
    subagentIndex = 1,
    subagentPreset = null,
    subagentDescription = "检查构建脚本",
    toolName = "shell_exec",
    risk = ToolRisk.PROCESS,
    target = """["process","builtin","./gradlew :app:dependencies"]""",
    preview = "./gradlew :app:dependencies --configuration sideloadDebugRuntimeClasspath",
    requestedAt = NOW,
)

@Composable
private fun SubagentFrame(dark: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
