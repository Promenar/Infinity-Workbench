package com.promenar.nexara.ui.rag

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
        "agentAdvanced" to source("ui/hub/AgentAdvancedRetrievalScreen.kt"),
    )

    @Test
    fun `all retrieval settings screens use theme roles without glass styling`() {
        sources.forEach { (key, screen) ->
            assertThat(screen).doesNotContain("NexaraGlassCard")
            assertThat(screen).doesNotContain("NexaraColors.")
            assertThat(screen).doesNotContain("GlassBorder")
            assertThat(screen).doesNotContain("Color(0x")
            assertThat(screen).doesNotContain("Card(")
            assertThat(screen).doesNotContain("Surface(")
            assertThat(screen.contains("MaterialTheme.colorScheme") || screen.contains("NexaraSettingsSection(")).isTrue()
            assertThat(screen.contains("MaterialTheme.typography") || screen.contains("NexaraSettingsSection(")).isTrue()
            if (key != "agentAdvanced") {
                assertThat(screen).contains("NexaraSettingsPageLayout(")
            }
        }
    }

    @Test
    fun `settings-owned retrieval pages use the dedicated se settings page layout`() {
        listOf("global", "advanced", "ragAdvanced", "debug", "graph").forEach { key ->
            val screen = sources.getValue(key)
            assertThat(screen).contains("NexaraSettingsPageLayout(")
            assertThat(screen).doesNotContain("NexaraPageLayout(")
        }
    }

    @Test
    fun `debug page is a flat settings surface without legacy cards or typography`() {
        val screen = sources.getValue("debug")
        assertThat(screen).contains("SettingsSectionHeader(")
        assertThat(screen).contains("HorizontalDivider(")
        assertThat(screen).doesNotContain("Surface(")
        assertThat(screen).doesNotContain("NexaraShapes")
        assertThat(screen).doesNotContain("NexaraTypography")
    }

    @Test
    fun `知识库设置用分段控件选检索深度且自定义段不可点`() {
        val screen = sources.getValue("global")
        assertThat(screen).contains("NexaraSegmentedControl(")
        assertThat(screen).contains("clickable = false")
        assertThat(screen).doesNotContain("NexaraCapsuleTabRow(")
        assertThat(screen).contains("NexaraSettingsSection(")
        assertThat(screen).contains("KnowledgeModelRow(")
        assertThat(screen).doesNotContain("NexaraSlider(")
    }

    @Test
    fun `调试与高级用折叠行承载原始参数且清除向量走确认`() {
        val screen = sources.getValue("advanced")
        assertThat(screen).contains("NexaraSettingsExpandableRow(")
        assertThat(screen).contains("NexaraSettingsSection(")
        assertThat(screen).doesNotContain("ListItem(")
        assertThat(screen).contains("NexaraNumericSetting(")
        assertThat(screen).doesNotContain("NexaraSlider(")
        assertThat(screen).doesNotContain("Modifier.alpha(")
        assertThat(screen).contains("NexaraConfirmDialog(")
        assertThat(screen).doesNotContain("AlertDialog(")
        // 高级参数展开状态不跨页面保留（A1）。
        assertThat(screen).doesNotContain("rememberSaveable")
    }

    @Test
    fun `知识图谱页列出抽取预算与抽取模型`() {
        val screen = sources.getValue("ragAdvanced")
        assertThat(screen).contains("NexaraSettingsSection(")
        assertThat(screen).contains("KgBudgetRows(")
        assertThat(screen).contains("KnowledgeModelRow(")
        assertThat(screen).doesNotContain("ListItem(")
    }

    @Test
    fun `agent retrieval keeps independent state with standard controls`() {
        val screen = sources.getValue("agentAdvanced")
        assertThat(screen).contains("AgentEditViewModel")
        assertThat(screen).contains("NexaraSettingsSection(")
        assertThat(screen).contains("SettingsToggle(")
        assertThat(screen).contains("RetrievalStrategySetting(")
        assertThat(screen).contains("NexaraNumericSetting(")
        assertThat(screen).doesNotContain("NexaraSlider(")
        assertThat(screen).contains("NexaraConfirmDialog(")
        assertThat(screen).doesNotContain("AlertDialog(")
    }

    private fun source(relativePath: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/$relativePath"),
    ).toString(Charsets.UTF_8)
}
