package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class RagSettingsMaterialContractTest {
    private val sources = mapOf(
        "global" to source("ui/rag/GlobalRagConfigScreen.kt"),
        "search" to source("ui/settings/SearchConfigScreen.kt"),
        "advanced" to source("ui/rag/AdvancedRetrievalScreen.kt"),
        "ragAdvanced" to source("ui/rag/RagAdvancedScreen.kt"),
        "agentAdvanced" to source("ui/hub/AgentAdvancedRetrievalScreen.kt"),
    )

    @Test
    fun `all retrieval settings screens use theme roles without glass styling`() {
        sources.values.forEach { screen ->
            assertThat(screen).doesNotContain("NexaraGlassCard")
            assertThat(screen).doesNotContain("NexaraColors.")
            assertThat(screen).doesNotContain("GlassBorder")
            assertThat(screen).doesNotContain("Color(0x")
            assertThat(screen).doesNotContain("Card(")
            assertThat(screen).doesNotContain("Surface(")
            assertThat(screen).contains("MaterialTheme.colorScheme")
            assertThat(screen).contains("MaterialTheme.typography")
            assertThat(screen).contains("NexaraPageLayout(")
        }
    }

    @Test
    fun `global configuration uses segmented presets rows sliders and confirmation`() {
        val screen = sources.getValue("global")
        assertThat(screen).contains("SingleChoiceSegmentedButtonRow(")
        assertThat(screen).contains("SegmentedButton(")
        assertThat(screen).contains("ListItem(")
        assertThat(screen).contains("Slider(")
        assertThat(screen).contains("AlertDialog(")
        assertThat(screen).contains("TextButton(")
    }

    @Test
    fun `search configuration uses standard list toggles engine choice and sliders`() {
        val screen = sources.getValue("search")
        assertThat(screen).contains("ListItem(")
        assertThat(screen).contains("Switch(")
        assertThat(screen).contains("RadioButton(")
        assertThat(screen).contains("Slider(")
        assertThat(screen).contains(".toggleable(")
        assertThat(screen).contains(".selectable(")
        assertThat(screen).contains("onCheckedChange = null")
        assertThat(screen).contains("onClick = null")
    }

    @Test
    fun `global and advanced retrieval pages use continuous list sections`() {
        listOf("advanced", "ragAdvanced").forEach { key ->
            val screen = sources.getValue(key)
            assertThat(screen).contains("ListItem(")
            assertThat(screen).contains("HorizontalDivider(")
            assertThat(screen).contains("Slider(")
            assertThat(screen).doesNotContain("Modifier.alpha(")
        }
    }

    @Test
    fun `agent retrieval keeps independent state with standard controls`() {
        val screen = sources.getValue("agentAdvanced")
        assertThat(screen).contains("AgentEditViewModel")
        assertThat(screen).contains("ListItem(")
        assertThat(screen).contains("Switch(")
        assertThat(screen).contains("FilterChip(")
        assertThat(screen).contains("Slider(")
        assertThat(screen).contains("AlertDialog(")
    }

    private fun source(relativePath: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/$relativePath"),
    ).toString(Charsets.UTF_8)
}
