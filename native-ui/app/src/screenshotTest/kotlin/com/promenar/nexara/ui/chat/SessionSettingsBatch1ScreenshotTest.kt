package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillSnapshot
import com.promenar.nexara.data.skills.SkillStatus
import com.promenar.nexara.ui.skills.SessionSkillsSections
import com.promenar.nexara.ui.skills.SkillCatalogActions
import com.promenar.nexara.ui.skills.SkillCatalogUiState
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.*

@PreviewTest
@Preview(name = "会话模型 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话模型 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionModelBatch1Preview() = SessionFrame(0) {
    ModelPanel("preview-model", {}, listOf(ModelInfo(id = "preview-model", name = "示例对话模型", description = "", type = "chat", providerId = "preview", enabled = true)))
}

@PreviewTest
@Preview(name = "会话参数 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话参数 深中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun sessionParamsBatch1Preview() = SessionFrame(1) { SessionParamsContent(InferenceParams()) }

@PreviewTest
@Preview(name = "会话工具 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话工具 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionToolsBatch1Preview() = SessionFrame(2) { SessionToolsContent() }

@PreviewTest
@Preview(name = "会话工具 技能 深中411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Preview(name = "会话工具 技能 深中360 字号130", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionToolsSkillsPreview() = SessionFrame(2) { SessionToolsSkillsSample() }

@PreviewTest
@Preview(name = "会话工具 技能 浅中411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Composable
fun sessionToolsSkillsLightPreview() = SessionFrame(2, light = true) { SessionToolsSkillsSample() }

@Composable
private fun SessionToolsSkillsSample() {
    SessionToolsContent(skillsSection = {
        SessionSkillsSections(
            SkillCatalogUiState(
                loading = false,
                projectAvailable = true,
                snapshot = SkillSnapshot(
                    workspace = null,
                    skills = listOf(
                        previewSkill("pdf-report", "把分析结果整理为带目录与图表的 PDF 报告。"),
                        previewSkill("git-release", "按仓库约定生成版本说明并打标签。", modified = true),
                        previewSkill("meeting-notes", "从会议录音转写中提炼决议与待办。", status = SkillStatus.DISABLED, implicit = false),
                        previewSkill("android-build", "构建并校验两个发行渠道的 APK。", scope = SkillScope.PROJECT),
                        previewSkill("db-migration", "为 Room 迁移编写测试并核对 schema。", scope = SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST),
                        previewSkill("broken-skill", "", scope = SkillScope.PROJECT, status = SkillStatus.BROKEN, reason = "name 与目录名不一致"),
                    ),
                ),
            ),
            SkillCatalogActions(),
        )
    })
}

private fun previewSkill(
    name: String,
    description: String,
    scope: SkillScope = SkillScope.USER,
    status: SkillStatus = SkillStatus.ENABLED,
    modified: Boolean = false,
    implicit: Boolean = true,
    reason: String? = null,
) = DiscoveredSkill(
    id = "${scope.name.lowercase()}:$name",
    scope = scope,
    name = name,
    description = description,
    displayName = null,
    shortDescription = null,
    virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
    files = emptyList(),
    status = status,
    modifiedSinceConfirmed = modified,
    allowImplicitInvocation = implicit,
    brokenReason = reason,
    metadata = null,
)

@PreviewTest
@Preview(name = "会话上下文 深中411", widthDp = 411, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中360", widthDp = 360, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中130", widthDp = 411, heightDp = 1400, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionContextBatch1Preview() = SessionFrame(3) { SessionContextPanelContent() }

/** 会话档位超出助手上限（对话记忆存为“全部助手”，助手上限为“本助手”），以及文档检索关闭。 */
@PreviewTest
@Preview(name = "会话检索范围 受限 浅中411", widthDp = 411, heightDp = 1180, locale = "zh-rCN")
@Preview(name = "会话检索范围 受限 浅中360 字号130", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionRetrievalScopeCappedPreview() = SessionFrame(3, light = true) {
    SessionContextPanelContent(
        ragOptions = com.promenar.nexara.data.model.RagOptions(
            enableDocs = false,
            enableKnowledgeGraph = true,
            memoryScope = com.promenar.nexara.data.model.ConversationScope.ALL_AGENTS,
            graphScope = com.promenar.nexara.data.model.ConversationScope.AGENT,
        ),
        memoryPolicy = com.promenar.nexara.data.agent.AgentMemoryPolicy(
            memoryCap = com.promenar.nexara.data.model.ConversationScope.AGENT,
        ),
    )
}

/** 档位选择面板内容：超出助手上限的档位不可选并注明原因。 */
@PreviewTest
@Preview(name = "检索范围选择 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Preview(name = "检索范围选择 深中360 字号130", widthDp = 360, heightDp = 560, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun retrievalScopeChoicesPreview() = SessionFrame(3) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        ScopeChoiceList(ScopeResource.Memory, selectedOrdinal = 1, onSelect = {}, maxOrdinal = 1)
    }
}

/** 助手编辑页“记忆与知识”分组：私密助手，对话记忆上限为“本助手”。 */
@PreviewTest
@Preview(name = "助手记忆与知识 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "助手记忆与知识 深中360 字号130", widthDp = 360, heightDp = 860, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentMemoryPolicyPreview() = SessionFrame(3) {
    Column(Modifier.padding(16.dp)) {
        com.promenar.nexara.ui.hub.AgentMemoryPolicySection(
            policy = com.promenar.nexara.data.agent.AgentMemoryPolicy(
                memoryCap = com.promenar.nexara.data.model.ConversationScope.AGENT,
                shareWithOtherAgents = false,
            ),
            onPolicyChange = {},
        )
    }
}

@Composable
private fun SessionFrame(index: Int, light: Boolean = false, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (light) NexaraThemeMode.LIGHT else NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                NexaraCapsuleTabRow(tabs = listOf(R.string.sheet_tab_model, R.string.sheet_tab_thinking,
                    R.string.sheet_tab_tools, R.string.sheet_tab_settings).map { NexaraTabItem(stringResource(it)) },
                    selectedIndex = index, onTabSelected = {}, modifier = Modifier.padding(horizontal = 16.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
