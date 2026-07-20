package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Task 3 红色约束：管理页共享原语与页面骨架在未落地前必须失败，避免实现绕道。
 */
class Md3ConvergencePrimitivesContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    private fun sourceFile(name: String) = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/$name.kt",
    )

    @Test
    fun `missing NexaraSettingsSection should be added for sectionized settings lists`() {
        val sourceFile = sourceFile("NexaraSettingsSection")
        assertThat(sourceFile.exists()).isTrue()
    }

    @Test
    fun `NexaraSettingsSection should use list-vertical tokens without glass card`() {
        val source = sourceFile("NexaraSettingsSection").readText()
        assertThat(source).contains("SettingsSectionHeader")
        assertThat(source).contains("Divider")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).doesNotContain("NexaraGlassCard(")
    }

    @Test
    fun `missing NexaraSearchTopBar should be added with query and active callbacks`() {
        val sourceFile = sourceFile("NexaraSearchTopBar")
        assertThat(sourceFile.exists()).isTrue()
    }

    @Test
    fun `NexaraSearchTopBar should expose back actions search actions and default action slot`() {
        val source = sourceFile("NexaraSearchTopBar").readText()
        assertThat(source).contains("fun NexaraSearchTopBar(")
        assertThat(source).contains("onBack: (() -> Unit)?")
        assertThat(source).contains("onQueryChange: (String) -> Unit")
        assertThat(source).contains("onSearchActiveChange: (Boolean) -> Unit")
        assertThat(source).contains("searchActive: Boolean")
        assertThat(source).contains("title: String")
        assertThat(source).contains("actions: @Composable RowScope.() -> Unit = {}")
        assertThat(source).contains("Modifier.size(NexaraSpacing.MinimumTouchTarget)")
        assertThat(source).contains("KeyboardActions(")
        assertThat(source).contains("ImeAction.Search")
        assertThat(source).contains("FocusRequester")
        assertThat(source).doesNotContain("NexaraSearchBar(")
    }

    @Test
    fun `NexaraPageLayout should own material insets and ime padding`() {
        val source = sourceFile("NexaraPageLayout").readText()
        assertThat(source).contains("WindowInsets.systemBars")
        assertThat(source).contains("imePadding()")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("containerColor = MaterialTheme.colorScheme.background")
        assertThat(source).contains("Scaffold(")
    }
}
