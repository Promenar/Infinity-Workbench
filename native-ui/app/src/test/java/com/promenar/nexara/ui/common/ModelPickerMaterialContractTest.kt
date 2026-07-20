package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Task 7 RED — 锁定统一模型选择表面的 Material 3 视觉合同。
 *
 * 模型选择器必须使用连续 ListItem、secondaryContainer tonal 选中态、稳定 key、
 * selected 语义和勾选尾标；共享 [ModelSelectionListItem]；最多两个能力摘要；
 * 禁止 NexaraGlassCard、硬编码能力色以及旧 UI 专用 ModelItem / ModelCapability。
 *
 * 见 docs/superpowers/specs/2026-07-20-nexara-md3-convergence-design.md §9。
 */
class ModelPickerMaterialContractTest {

    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    private fun commonSourceFile(name: String): File = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/$name.kt",
    )

    private fun commonSource(name: String): String = commonSourceFile(name).readText()

    private fun chatSource(name: String): String = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/$name.kt",
    ).readText()

    private fun source(relativePath: String): String = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/$relativePath.kt",
    ).readText()

    // ------------------------------------------------------------------
    // 共享 ModelSelectionListItem
    // ------------------------------------------------------------------

    @Test
    fun `ModelSelectionListItem source exists`() {
        assertThat(commonSourceFile("ModelSelectionListItem").exists()).isTrue()
    }

    @Test
    fun `selection list item renders continuous Material ListItem with headline and supporting slots`() {
        val source = commonSource("ModelSelectionListItem")

        assertThat(source).contains("fun ModelSelectionListItem(")
        assertThat(source).contains("ListItem(")
        assertThat(source).contains("headlineContent")
        assertThat(source).contains("supportingContent")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).doesNotContain("NexaraGlassCard(")
        assertThat(source).doesNotContain("Surface(")
    }

    @Test
    fun `selection list item uses secondaryContainer tonal highlight for selected state`() {
        val source = commonSource("ModelSelectionListItem")

        assertThat(source).contains("secondaryContainer")
    }

    @Test
    fun `selection list item exposes selected semantics and check trailing icon`() {
        val source = commonSource("ModelSelectionListItem")

        assertThat(source).contains("semantics")
        assertThat(source).contains("selected")
        assertThat(source).contains("Icons.Rounded.Check")
    }

    @Test
    fun `selection list item caps capability summaries at two`() {
        val source = commonSource("ModelSelectionListItem")

        assertThat(source).contains(".take(2)")
    }

    @Test
    fun `selection list item rejects hard-coded capability colors`() {
        val source = commonSource("ModelSelectionListItem")

        assertThat(source).doesNotContain("capabilityColors")
        assertThat(source).doesNotContain("capabilityColorMap")
        assertThat(source).doesNotContain("Color(0x")
    }

    // ------------------------------------------------------------------
    // ModelPicker 消费共享行
    // ------------------------------------------------------------------

    @Test
    fun `ModelPicker renders shared selection row with stable lazy key`() {
        val source = commonSource("ModelPicker")

        assertThat(source).contains("ModelSelectionListItem(")
        assertThat(source).contains("itemsIndexed(")
        assertThat(source).contains("key =")
        assertThat(source).doesNotContain("NexaraGlassCard(")
    }

    @Test
    fun `ModelPicker no longer declares legacy UI ModelItem or ModelCapability`() {
        val source = commonSource("ModelPicker")

        assertThat(source).doesNotContain("data class ModelItem")
        assertThat(source).doesNotContain("enum class ModelCapability")
        assertThat(source).doesNotContain("capabilityColors")
    }

    // ------------------------------------------------------------------
    // SessionSettingsSheet 消费共享行
    // ------------------------------------------------------------------

    @Test
    fun `SessionSettingsSheet consumes shared selection row without legacy model item`() {
        val source = chatSource("SessionSettingsSheet")

        assertThat(source).contains("ModelSelectionListItem(")
        assertThat(source).contains("key =")
        assertThat(source).doesNotContain("ModelItem(")
        assertThat(source).doesNotContain("capabilityColorMap")
    }

    @Test
    fun `all current model picker producers use the frozen projection without legacy UI types`() {
        val producerSources = listOf(
            source("ui/hub/AgentHubScreen"),
            source("ui/hub/UserSettingsHomeScreen"),
            source("ui/rag/RagAdvancedScreen"),
            source("ui/rag/RagViewModel"),
        )

        producerSources.forEach { source ->
            assertThat(source).doesNotContain("ModelItem(")
            assertThat(source).doesNotContain("ui.common.ModelItem")
            assertThat(source).doesNotContain("ui.common.ModelCapability")
        }
    }
}
