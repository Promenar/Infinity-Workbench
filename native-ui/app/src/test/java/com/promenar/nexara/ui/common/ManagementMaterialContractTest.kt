package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 锁定管理页面共享组件的 Material 3 基线，避免重新引入玻璃卡、过小触控目标或
 * 绕过主题 token 的页面节奏。
 */
class ManagementMaterialContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    private fun source(name: String): String = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/$name.kt",
    ).readText()

    private fun sourceFile(name: String) = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/$name.kt",
    )

    @Test
    fun `settings item is a flexible transparent material list row with button semantics`() {
        val source = source("NexaraSettingsItem")

        assertThat(source).contains("ListItem(")
        assertThat(source).contains("ListItemDefaults.colors(")
        assertThat(source).contains("containerColor = Color.Transparent")
        assertThat(source).doesNotContain("Surface(")
        assertThat(source).doesNotContain("NexaraGlassCard(")
        assertThat(source).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(source).contains(".fillMaxWidth()")
        assertThat(source).contains("role = Role.Button")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("MaterialTheme.colorScheme")
    }

    @Test
    fun `search top bar contract exists and exposes state plus actions`() {
        val searchTopBarSourceFile = sourceFile("NexaraSearchTopBar")
        assertThat(searchTopBarSourceFile.exists()).isTrue()

        val source = searchTopBarSourceFile.readText()

        assertThat(source).contains("IconButton(")
        assertThat(source).contains("fun NexaraSearchTopBar(")
        assertThat(source).contains("onSearchActiveChange: (Boolean) -> Unit")
        assertThat(source).contains("onQueryChange: (String) -> Unit")
        assertThat(source).contains("searchActive: Boolean")
        assertThat(source).contains("actions: @Composable RowScope.() -> Unit")
        assertThat(source).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(source).doesNotContain("Modifier.size(24.dp)")
        assertThat(source).doesNotContain("NexaraSearchBar(")
        assertThat(source).contains("MaterialTheme.colorScheme")
    }

    @Test
    fun `page layout owns insets and uses opaque material tokens`() {
        val source = source("NexaraPageLayout")

        assertThat(source).contains("contentWindowInsets = WindowInsets.systemBars")
        assertThat(source).contains("Modifier.imePadding()")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("MaterialTheme.typography.titleLarge")
        assertThat(source).contains("NexaraSpacing.ScreenHorizontal")
        assertThat(source).doesNotContain("NexaraTypography.headlineLarge")
        assertThat(source).doesNotContain("CanvasBackground.copy(alpha = 0.8f)")
        assertThat(source).doesNotContain("padding(horizontal = 20.dp, vertical = 24.dp)")
    }

    @Test
    fun `settings section primitive should not regress to glass card or per-row surface`() {
        val sectionSourceFile = sourceFile("NexaraSettingsSection")
        assertThat(sectionSourceFile.exists()).isTrue()

        val source = sectionSourceFile.readText()
        assertThat(source).contains("fun NexaraSettingsSection")
        assertThat(source).contains("HorizontalDivider(")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).doesNotContain("NexaraGlassCard(")
        assertThat(source).doesNotContain("Surface(")
    }
}
