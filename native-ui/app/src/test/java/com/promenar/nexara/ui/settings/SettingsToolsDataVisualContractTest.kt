package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class SettingsToolsDataVisualContractTest {
    @Test
    fun `工具与数据深层页统一使用设置骨架`() {
        listOf(
            "SkillsScreen.kt",
            "TokenUsageScreen.kt",
            "BackupSettingsScreen.kt",
            "LocalModelsScreen.kt",
        ).forEach { fileName ->
            val source = source(fileName)

            assertThat(source).contains("NexaraSettingsPageLayout(")
            assertThat(source).doesNotContain("NexaraPageLayout(")
            assertThat(source).doesNotContain("Scaffold(")
            assertThat(source).doesNotContain("TopAppBar(")
        }
    }

    @Test
    fun `数据和孤立配置页使用小号分组标题`() {
        listOf(
            "TokenUsageScreen.kt",
            "BackupSettingsScreen.kt",
            "LocalModelsScreen.kt",
        ).forEach { fileName ->
            assertThat(source(fileName)).contains("SettingsSectionHeader(")
        }
    }

    private fun source(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/settings/$fileName"),
    ).toString(Charsets.UTF_8)
}
