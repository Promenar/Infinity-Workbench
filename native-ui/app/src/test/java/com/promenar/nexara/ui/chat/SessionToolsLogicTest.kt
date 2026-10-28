package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillSnapshot
import com.promenar.nexara.data.skills.SkillStatus
import com.promenar.nexara.data.skills.SkillWorkspace
import com.promenar.nexara.ui.chat.command.slashTestSkill
import com.promenar.nexara.ui.skills.SkillCatalogUiState
import java.io.File
import org.junit.Test

class SessionToolsLogicTest {

    @Test
    fun `工具开启时没有关闭状态，关闭后按保留联网搜索与模型能力区分`() {
        assertThat(toolsOffState(SessionOptions(toolsEnabled = true), toolCallingSupported = true)).isNull()
        assertThat(toolsOffState(SessionOptions(toolsEnabled = false, keepWebSearchWhenToolsOff = true), toolCallingSupported = true))
            .isEqualTo(ToolsOffState.WebSearchOnly)
        assertThat(toolsOffState(SessionOptions(toolsEnabled = false, keepWebSearchWhenToolsOff = false), toolCallingSupported = true))
            .isEqualTo(ToolsOffState.NoTools)
        // 模型不支持工具调用时，保留联网搜索也不起作用。
        assertThat(toolsOffState(SessionOptions(toolsEnabled = false, keepWebSearchWhenToolsOff = true), toolCallingSupported = false))
            .isEqualTo(ToolsOffState.NoTools)
    }

    @Test
    fun `只有模型目录明确不支持工具调用时才算不支持`() {
        val unknown = ModelInfo(name = "A", id = "p:a", description = "", enabled = true)
        val supported = unknown.copy(id = "p:b", capabilities = listOf("toolcalling"))
        val unsupported = unknown.copy(id = "p:c", capabilities = listOf("vision"), userEditedFields = setOf("capabilities"))
        val models = listOf(unknown, supported, unsupported)

        assertThat(modelSupportsToolCalling("p:a", models)).isTrue()
        assertThat(modelSupportsToolCalling("p:b", models)).isTrue()
        assertThat(modelSupportsToolCalling("p:c", models)).isFalse()
        assertThat(modelSupportsToolCalling("p:missing", models)).isTrue()
        assertThat(modelSupportsToolCalling(null, models)).isTrue()
    }

    @Test
    fun `技能摘要首次扫描前不显示，扫描后统计已启用用户技能与项目技能`() {
        assertThat(sessionSkillsSummary(SkillCatalogUiState(loading = true))).isNull()
        assertThat(sessionSkillsSummary(SkillCatalogUiState(loading = false))).isEqualTo(SessionSkillsSummary(0, null, null))

        val snapshot = SkillSnapshot(
            workspace = SkillWorkspace(key = "ws", root = File("/data/workspaces/demo")),
            skills = listOf(
                slashTestSkill("a"),
                slashTestSkill("b", status = SkillStatus.DISABLED),
                slashTestSkill("c"),
                slashTestSkill("p1", scope = SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST),
                slashTestSkill("p2", scope = SkillScope.PROJECT),
            ),
        )
        assertThat(sessionSkillsSummary(SkillCatalogUiState(loading = false, snapshot = snapshot, projectAvailable = true)))
            .isEqualTo(SessionSkillsSummary(enabledUserSkills = 2, projectSkills = 2, workspaceName = "demo"))
        // 会话没有目录型工作区时不显示项目技能；重扫期间沿用上次快照。
        assertThat(sessionSkillsSummary(SkillCatalogUiState(loading = true, snapshot = snapshot, projectAvailable = false)))
            .isEqualTo(SessionSkillsSummary(enabledUserSkills = 2, projectSkills = null, workspaceName = null))
    }
}
