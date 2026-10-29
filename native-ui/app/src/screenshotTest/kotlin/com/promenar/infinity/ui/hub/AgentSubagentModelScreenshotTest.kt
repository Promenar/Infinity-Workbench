package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.data.subagent.SubagentRun
import com.promenar.infinity.data.subagent.SubagentStatus
import com.promenar.infinity.data.subagent.SubagentToolProfile
import com.promenar.infinity.ui.chat.SubagentGroupCardContent
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "子代理设置 跟随会话 换模型关 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Preview(name = "Subagent settings follow switch off EN 360", widthDp = 360, heightDp = 460, locale = "en")
@Composable
fun agentSubagentModelOffPreview() = SubagentModelFrame {
    AgentSubagentModelSection(
        policy = AgentSubagentModelPolicy(),
        available = PreviewModels,
        displayName = ::previewName,
        onPolicyChange = {},
    )
}

@PreviewTest
@Preview(name = "子代理设置 指定模型 换模型开 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "Subagent settings fixed switch on EN 360", widthDp = 360, heightDp = 600, locale = "en")
@Composable
fun agentSubagentModelOnPreview() = SubagentModelFrame { SubagentModelOnSection() }

@PreviewTest
@Preview(name = "子代理设置 指定模型 换模型开 浅中360 字号130", widthDp = 360, heightDp = 760, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentSubagentModelOnLightPreview() = SubagentModelFrame(dark = false) { SubagentModelOnSection() }

@Composable
private fun SubagentModelOnSection() = AgentSubagentModelSection(
    policy = AgentSubagentModelPolicy(
        defaultModelId = "deepseek::deepseek-v4-flash",
        allowPerTaskModel = true,
        // 最后一项已停用，标为不可用。
        selectableModelIds = listOf("anthropic::claude-opus-5-5", "openai::gpt-6.1", "deepseek::deepseek-v4-flash", "old::retired-model"),
    ),
    available = PreviewModels,
    displayName = ::previewName,
    onPolicyChange = {},
)

@PreviewTest
@Preview(name = "子代理设置 换模型开 未添加 深中411", widthDp = 411, heightDp = 480, locale = "zh-rCN")
@Composable
fun agentSubagentModelEmptyListPreview() = SubagentModelFrame {
    AgentSubagentModelSection(
        policy = AgentSubagentModelPolicy(allowPerTaskModel = true),
        available = PreviewModels,
        displayName = ::previewName,
        onPolicyChange = {},
    )
}

@PreviewTest
@Preview(name = "子代理卡片 模型标注 深中411", widthDp = 411, heightDp = 380, locale = "zh-rCN")
@Preview(name = "Subagent card model labels EN 360 font130", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable
fun subagentCardModelLabelPreview() = SubagentModelFrame {
    SubagentGroupCardContent(
        SubagentBatch(
            sessionId = "session",
            stepId = "step",
            runs = listOf(
                previewRun(0, "deepseek::deepseek-v4-flash", chosenByAgent = false, SubagentStatus.RUNNING, "检索 Room 迁移入口").copy(step = 3, currentTool = "read_file"),
                previewRun(1, "anthropic::claude-opus-5-5", chosenByAgent = true, SubagentStatus.COMPLETED, "复审迁移方案").copy(
                    endedAt = NOW - 4_000,
                    result = "## 结论\n迁移入口集中在 InfinityDatabaseMigrations。",
                ),
            ),
        ),
        onOpen = {},
        now = NOW,
        modelName = ::previewName,
    )
}

private const val NOW = 1_000_000L

private val PreviewNames = mapOf(
    "anthropic::claude-opus-5-5" to "Claude Opus 5.5",
    "openai::gpt-6.1" to "GPT 6.1",
    "deepseek::deepseek-v4-flash" to "DeepSeek V4 Flash",
    "old::retired-model" to "Retired Model",
)

private fun previewName(id: String): String = PreviewNames[id] ?: id

private val PreviewModels = PreviewNames.keys.filter { it != "old::retired-model" }.map { id ->
    ModelSelectionUiModel(
        selectionId = id,
        remoteModelId = id.substringAfter("::"),
        displayName = previewName(id),
        providerName = id.substringBefore("::"),
        contextTokens = 128_000,
        workload = ModelWorkload.GENERATIVE_TEXT,
        capabilityStates = mapOf(ModelCapability.TOOL_CALLING to SupportState.SUPPORTED),
        chatEndpointCompatible = SupportState.SUPPORTED,
    )
}

private fun previewRun(index: Int, modelId: String, chosenByAgent: Boolean, status: SubagentStatus, description: String) = SubagentRun(
    index = index,
    preset = if (index == 0) "explorer" else null,
    description = description,
    prompt = description,
    toolProfile = if (index == 0) SubagentToolProfile.READ_ONLY else SubagentToolProfile.INHERIT,
    modelId = modelId,
    modelChosenByAgent = chosenByAgent,
    maxSteps = 0,
    status = status,
    startedAt = NOW - 42_000,
)

@Composable
private fun SubagentModelFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}
