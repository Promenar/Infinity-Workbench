package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class RagSettingsMaterialContractTest {
    private val sources = mapOf(
        "global" to source("ui/rag/KnowledgeBaseSettingsScreen.kt"),
        "advanced" to source("ui/rag/KnowledgeDebugSettingsScreen.kt"),
        "ragAdvanced" to source("ui/rag/KnowledgeGraphSettingsScreen.kt"),
        "debug" to source("ui/rag/RagDebugScreen.kt"),
        "graph" to source("ui/rag/KnowledgeGraphScreen.kt"),
        "agentKnowledge" to source("ui/hub/AgentEditSections.kt"),
    )

    @Test
    fun `all retrieval settings screens use theme roles without glass styling`() {
        sources.forEach { (key, screen) ->
            assertThat(screen).doesNotContain("InfinityGlassCard")
            assertThat(screen).doesNotContain("InfinityColors.")
            assertThat(screen).doesNotContain("GlassBorder")
            assertThat(screen).doesNotContain("Color(0x")
            assertThat(screen).doesNotContain("Card(")
            assertThat(screen).doesNotContain("Surface(")
            assertThat(screen.contains("MaterialTheme.colorScheme") || screen.contains("InfinitySettingsSection(")).isTrue()
            assertThat(screen.contains("MaterialTheme.typography") || screen.contains("InfinitySettingsSection(")).isTrue()
            if (key != "agentKnowledge") {
                assertThat(screen).contains("InfinitySettingsPageLayout(")
            }
        }
    }

    @Test
    fun `settings-owned retrieval pages use the dedicated se settings page layout`() {
        listOf("global", "advanced", "ragAdvanced", "debug", "graph").forEach { key ->
            val screen = sources.getValue(key)
            assertThat(screen).contains("InfinitySettingsPageLayout(")
            assertThat(screen).doesNotContain("InfinityPageLayout(")
        }
    }

    @Test
    fun `debug page is a flat settings surface without legacy cards or typography`() {
        val screen = sources.getValue("debug")
        assertThat(screen).contains("SettingsSectionHeader(")
        assertThat(screen).contains("HorizontalDivider(")
        assertThat(screen).doesNotContain("Surface(")
        assertThat(screen).doesNotContain("InfinityShapes")
        assertThat(screen).doesNotContain("InfinityTypography")
    }

    @Test
    fun `知识库设置用分段控件选检索深度且自定义段不可点`() {
        val screen = sources.getValue("global")
        assertThat(screen).contains("InfinitySegmentedControl(")
        assertThat(screen).contains("clickable = false")
        assertThat(screen).doesNotContain("InfinityCapsuleTabRow(")
        assertThat(screen).contains("InfinitySettingsSection(")
        assertThat(screen).contains("KnowledgeModelRow(")
        assertThat(screen).doesNotContain("InfinitySlider(")
    }

    @Test
    fun `调试与高级用折叠行承载原始参数且清除向量走确认`() {
        val screen = sources.getValue("advanced")
        assertThat(screen).contains("InfinitySettingsExpandableRow(")
        assertThat(screen).contains("InfinitySettingsSection(")
        assertThat(screen).doesNotContain("ListItem(")
        assertThat(screen).contains("InfinityNumericSetting(")
        assertThat(screen).doesNotContain("InfinitySlider(")
        assertThat(screen).doesNotContain("Modifier.alpha(")
        assertThat(screen).contains("InfinityConfirmDialog(")
        assertThat(screen).doesNotContain("AlertDialog(")
        // 高级参数展开状态不跨页面保留（A1）。
        assertThat(screen).doesNotContain("rememberSaveable")
    }

    @Test
    fun `知识图谱页列出抽取预算与抽取模型`() {
        val screen = sources.getValue("ragAdvanced")
        assertThat(screen).contains("InfinitySettingsSection(")
        assertThat(screen).contains("KgBudgetRows(")
        assertThat(screen).contains("KnowledgeModelRow(")
        assertThat(screen).doesNotContain("ListItem(")
    }

    @Test
    fun `智能体知识设置用值行与单选面板且不再覆盖检索参数`() {
        val screen = sources.getValue("agentKnowledge")
        assertThat(screen).contains("InfinitySettingsSection(")
        assertThat(screen).contains("InfinityValueRow(")
        assertThat(screen).contains("InfinityBottomSheet(")
        assertThat(screen).contains("Role.RadioButton")
        assertThat(screen).doesNotContain("InfinityNumericSetting(")
        assertThat(screen).doesNotContain("InfinitySlider(")
        assertThat(screen).doesNotContain("AlertDialog(")
    }

    private fun source(relativePath: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/$relativePath"),
    ).toString(Charsets.UTF_8)
}
