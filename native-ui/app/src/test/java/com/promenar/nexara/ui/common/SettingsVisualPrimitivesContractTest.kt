package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class SettingsVisualPrimitivesContractTest {
    private fun source(fileName: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/ui/common/$fileName"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `settings page layout owns the settings app bar background and standard content padding`() {
        val source = source("NexaraSettingsPageLayout.kt")

        assertThat(source).contains("fun NexaraSettingsPageLayout(")
        assertThat(source).contains("TopAppBar(")
        assertThat(source).contains("NexaraBackButton(")
        assertThat(source).contains("containerColor = MaterialTheme.colorScheme.background")
        assertThat(source).contains("modifier = modifier.fillMaxSize()")
        assertThat(source).contains("val settingsTypography = remember(parentTypography)")
        assertThat(source).contains("typography = settingsTypography")
        assertThat(source).contains("titleLarge = parentTypography.titleLarge.copy")
        assertThat(source).contains("fontSize = 22.sp")
        assertThat(source).contains("bodyLarge = parentTypography.bodyLarge.copy")
        assertThat(source).contains("fontSize = 16.sp")
        assertThat(source).contains("bodyMedium = parentTypography.bodyMedium.copy")
        assertThat(source).contains("fontSize = 14.sp")
        assertThat(source).contains("labelSmall = parentTypography.labelSmall.copy")
        assertThat(source).contains("NexaraSpacing.ScreenHorizontal")
        assertThat(source).contains("content: @Composable (PaddingValues) -> Unit")
    }

    @Test
    fun `settings rows use standard md3 text roles without a default decorative chevron`() {
        val source = source("NexaraSettingsItem.kt")

        assertThat(source).contains("defaultMinSize(minHeight =")
        assertThat(source).contains("MaterialTheme.typography.bodyLarge")
        assertThat(source).contains("MaterialTheme.typography.bodyMedium")
        assertThat(source).contains("showChevron: Boolean = false")
        assertThat(source).doesNotContain("fontSize = 13.sp")
        assertThat(source).doesNotContain("fontSize = 12.sp")
        assertThat(source).contains("titleTextStyle: TextStyle? = null")
    }

    @Test
    fun `settings rows preserve an optional localized click label`() {
        val source = source("NexaraSettingsItem.kt")

        assertThat(source).contains("onClickLabel: String? = null")
        assertThat(source).contains("onClickLabel = onClickLabel")
    }

    @Test
    fun `settings section uses primary small heading and a full-width group-end divider`() {
        val section = source("NexaraSettingsSection.kt")
        val header = source("SettingsSectionHeader.kt")

        assertThat(header).contains("color = MaterialTheme.colorScheme.primary")
        assertThat(header).contains("MaterialTheme.typography.labelLarge")
        assertThat(section).contains("nexara_settings_section_divider")
        assertThat(section).doesNotContain("NexaraSpacing.ScreenHorizontal +")
    }
}
