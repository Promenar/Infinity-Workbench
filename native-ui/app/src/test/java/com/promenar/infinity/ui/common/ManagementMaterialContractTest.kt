package com.promenar.infinity.ui.common

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
        "src/main/java/com/promenar/infinity/ui/common/$name.kt",
    ).readText()

    private fun sourceFile(name: String) = projectRoot.resolve(
        "src/main/java/com/promenar/infinity/ui/common/$name.kt",
    )

    @Test
    fun `settings item is a flexible continuous row with button semantics`() {
        val source = source("InfinitySettingsItem")

        assertThat(source).contains("InfinitySettingsRowLayout(")
        assertThat(source).doesNotContain("ListItem(")
        assertThat(source).doesNotContain("Surface(")
        assertThat(source).doesNotContain("InfinityGlassCard(")
        assertThat(source).contains("role: Role = Role.Button")
        assertThat(source).contains("role = role")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("showChevron: Boolean = false")
    }

    @Test
    fun `search top bar contract exists and exposes state plus actions`() {
        val searchTopBarSourceFile = sourceFile("InfinitySearchTopBar")
        assertThat(searchTopBarSourceFile.exists()).isTrue()

        val source = searchTopBarSourceFile.readText()

        assertThat(source).contains("IconButton(")
        assertThat(source).contains("fun InfinitySearchTopBar(")
        assertThat(source).contains("val onActiveChange: (Boolean) -> Unit")
        assertThat(source).contains("val onQueryChange: (String) -> Unit")
        assertThat(source).contains("val active: Boolean")
        assertThat(source).contains("actions: @Composable RowScope.() -> Unit")
        assertThat(source).contains("InfinitySpacing.MinimumTouchTarget")
        assertThat(source).doesNotContain("Modifier.size(24.dp)")
        assertThat(source).contains("InfinitySearchBar(")
        assertThat(source).contains("MaterialTheme.colorScheme")
    }

    @Test
    fun `page layout owns insets and uses opaque material tokens`() {
        val source = source("InfinityPageLayout")

        // 整页时让出系统栏；放在宽屏检视器卡片里时卡片已避开系统栏，页面不再重复留白。
        assertThat(source).contains("InfinityPanePresentation.Inspector) WindowInsets(0) else WindowInsets.systemBars")
        assertThat(source).contains("contentWindowInsets = insets")
        assertThat(source).contains("Modifier.imePadding()")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("MaterialTheme.typography.titleLarge")
        assertThat(source).contains("InfinitySpacing.ScreenHorizontal")
        assertThat(source).doesNotContain("InfinityTypography.headlineLarge")
        assertThat(source).doesNotContain("CanvasBackground.copy(alpha = 0.8f)")
        assertThat(source).doesNotContain("padding(horizontal = 20.dp, vertical = 24.dp)")
    }

    @Test
    fun `settings section primitive should use Bettbox filled group surface`() {
        val sectionSourceFile = sourceFile("InfinitySettingsSection")
        assertThat(sectionSourceFile.exists()).isTrue()

        val source = sectionSourceFile.readText()
        val group = sourceFile("BettboxListGroup").readText()
        assertThat(source).contains("fun InfinitySettingsSection")
        assertThat(source).contains("BettboxListGroup(")
        assertThat(group).contains("MaterialTheme.colorScheme.surfaceContainerLow")
        assertThat(group).contains("RoundedCornerShape(20.dp)")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).doesNotContain("InfinityGlassCard(")
        assertThat(group).contains("Surface(")
    }
}
