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
import com.promenar.nexara.data.subagent.SubagentWait
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
}

@PreviewTest
@Preview(name = "子代理查看页 等待审批 浅色", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun subagentViewerWaitingLightPreview() = SubagentFrame(dark = false) {
    Column(modifier = Modifier.weight(1f)) {
        SubagentViewerContent(
            viewerRun().copy(
                description = "检查构建脚本",
                prompt = "运行 app 模块的依赖报告，列出重复引入的库。",
                toolProfile = SubagentToolProfile.INHERIT,
                status = SubagentStatus.WAITING_APPROVAL,
                currentTool = "shell_exec",
                endedAt = null,
                events = listOf(
                    SubagentEvent(SubagentEvent.Kind.TEXT, text = "先看依赖报告。", at = NOW - 20_000),
                    SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"command\":\"./gradlew :app:dependencies --configuration sideloadDebugRuntimeClasspath\",\"timeout_seconds\":120}", toolName = "shell_exec", at = NOW - 19_000),
                    SubagentEvent(SubagentEvent.Kind.APPROVAL, text = "./gradlew :app:dependencies", toolName = "shell_exec", at = NOW - 18_000),
                ),
                result = null,
            ),
            modelName = "DeepSeek V3.2",
            now = NOW,
        )
    }
    SubagentViewerFooter(onStop = {})
}

@PreviewTest
@Preview(name = "子代理分组卡 主会话等待中 深色", widthDp = 411, heightDp = 520, locale = "zh-rCN")
@Composable
fun subagentGroupCardWaitingDarkPreview() = SubagentFrame {
    SubagentGroupCardContent(backgroundBatch(zh = true), onOpen = {}, now = NOW, onDetach = {}, onStop = {})
}

@PreviewTest
@Preview(name = "Subagent group card background English 360dp font130", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun subagentGroupCardBackgroundEnglishPreview() = SubagentFrame(dark = false) {
    SubagentGroupCardContent(backgroundBatch(zh = false).copy(wait = null), onOpen = {}, now = NOW, onDetach = {}, onStop = {})
}

@PreviewTest
@Preview(name = "子代理查看页 主会话干预 深色", widthDp = 411, heightDp = 1300, locale = "zh-rCN")
@Composable
fun subagentViewerInterventionDarkPreview() = SubagentFrame {
    Column(modifier = Modifier.weight(1f)) {
        SubagentViewerContent(interventionRun(), modelName = "DeepSeek V4 Flash", now = NOW)
    }
    SubagentViewerFooter(onStop = {})
}

@PreviewTest
@Preview(name = "子代理审批 深色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun subagentApprovalDarkPreview() = SubagentFrame {
    SubagentApprovalContent(approval(), sessionTitle = "构建排查")
}

@PreviewTest
@Preview(name = "Subagent approval no target English font130", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun subagentApprovalNoTargetEnglishPreview() = SubagentFrame(dark = false) {
    SubagentApprovalContent(
        approval().copy(subagentPreset = null, subagentDescription = "Write a summary file", toolName = "write_file", risk = ToolRisk.FILE_WRITE, target = null, preview = "notes/summary.md"),
        sessionTitle = null,
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

private fun backgroundBatch(zh: Boolean) = SubagentBatch(
    sessionId = "session",
    stepId = "step",
    wait = SubagentWait(startedAt = NOW - 78_000, deadline = NOW + 522_000),
    runs = listOf(
        run(0, SubagentStatus.RUNNING, if (zh) "对比三家 SDK 退款接口" else "Compare refund APIs of three SDKs")
            .copy(id = "sa_a1b2c3", maxSteps = 0, step = 7, currentTool = "web_fetch", startedAt = NOW - 133_000),
        run(1, SubagentStatus.WRAPPING_UP, if (zh) "梳理现有退款流程" else "Map the current refund flow")
            .copy(id = "sa_d4e5f6", maxSteps = 0, step = 9, startedAt = NOW - 182_000),
        run(2, SubagentStatus.QUEUED, if (zh) "检查退款相关测试" else "Check refund tests").copy(id = "sa_g7h8i9", maxSteps = 0),
        run(3, SubagentStatus.STOPPED, if (zh) "查找旧版 SDK 文档" else "Find legacy SDK docs")
            .copy(id = "sa_j0k1l2", maxSteps = 0, step = 3, endedAt = NOW - 20_000),
    ),
)

private fun interventionRun() = run(1, SubagentStatus.WRAPPING_UP, "梳理现有退款流程").copy(
    id = "sa_d4e5f6",
    prompt = "找出所有调用旧支付 SDK 退款接口的位置，标出改造点。",
    maxSteps = 0,
    step = 9,
    startedAt = NOW - 182_000,
    totalTokens = 182_400,
    events = listOf(
        SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"pattern\":\"refund(\"}", toolName = "grep_files", at = NOW - 170_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_RESULT, text = "app/RefundService.kt:42", toolName = "grep_files", at = NOW - 168_000),
        SubagentEvent(SubagentEvent.Kind.DETACH, by = SubagentEvent.Actor.USER, at = NOW - 117_000),
        SubagentEvent(SubagentEvent.Kind.PARENT_MESSAGE, text = "只看 app/ 和 billing/ 两个模块，测试代码不用列。", by = SubagentEvent.Actor.PARENT, at = NOW - 82_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_CALL, text = "{\"path\":\"billing/BillingRefundGateway.kt\"}", toolName = "read_file", at = NOW - 60_000),
        SubagentEvent(SubagentEvent.Kind.TOOL_RESULT, text = "class BillingRefundGateway", toolName = "read_file", at = NOW - 59_000),
        SubagentEvent(SubagentEvent.Kind.WRAP_UP, text = "够用了，交回目前找到的改造点。", by = SubagentEvent.Actor.PARENT, at = NOW - 12_000),
        SubagentEvent(SubagentEvent.Kind.STOP, by = SubagentEvent.Actor.USER, at = NOW - 1_000),
    ),
    result = null,
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
