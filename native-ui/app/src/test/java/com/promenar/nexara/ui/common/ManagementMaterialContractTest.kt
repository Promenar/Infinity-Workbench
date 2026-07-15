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

    @Test
    fun `settings item is a flexible material surface with button semantics`() {
        val source = source("NexaraSettingsItem")

        assertThat(source).contains("Surface(")
        assertThat(source).doesNotContain("NexaraGlassCard(")
        assertThat(source).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(source).contains(".weight(1f)")
        assertThat(source).contains("role = Role.Button")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("MaterialTheme.colorScheme")
    }

    @Test
    fun `search clear action uses material icon button and a 48dp token target`() {
        val source = source("NexaraSearchBar")

        assertThat(source).contains("IconButton(")
        assertThat(source).contains("NexaraSpacing.MinimumTouchTarget")
        assertThat(source).doesNotContain("Modifier.size(24.dp)")
        assertThat(source).doesNotContain("NexaraGlassCard(")
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
}
