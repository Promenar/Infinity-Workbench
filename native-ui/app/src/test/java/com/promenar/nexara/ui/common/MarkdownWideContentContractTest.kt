package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class MarkdownWideContentContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String = String(
        Files.readAllBytes(moduleRoot.resolve(relativePath)),
        Charsets.UTF_8,
    )

    @Test
    fun `第三方 Markdown 回调不注册新的横向滚动`() {
        val markdownSource = source("src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt")
        val tableSource = source("src/main/java/com/promenar/nexara/ui/renderer/TableWidget.kt")
        val callbackSource = markdownSource
            .substringAfter("markdownComponents(")
            .substringBefore("\n    Markdown(\n")

        assertThat(callbackSource).doesNotContain("horizontalScroll(")
        assertThat(callbackSource).doesNotContain("rememberScrollState(")
        assertThat(tableSource).doesNotContain("horizontalScroll(")
        assertThat(tableSource).doesNotContain("rememberScrollState(")
    }

    @Test
    fun `单次 Markdown 成功回调只接管顶层代码节点`() {
        val markdownSource = source("src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt")
        val hostSource = markdownSource.substringAfter("private fun WideContentHost")

        assertThat(markdownSource).contains("success = { state, resolvedComponents, successModifier ->")
        assertThat(markdownSource).contains("state.node.children.forEach")
        assertThat(markdownSource).contains("MarkdownElement(node, resolvedComponents, state.content)")
        assertThat(markdownSource).contains("MarkdownElementTypes.PARAGRAPH")
        assertThat(markdownSource).contains("MarkdownElementTypes.CODE_FENCE")
        assertThat(markdownSource).contains("MarkdownElementTypes.CODE_BLOCK")
        assertThat(markdownSource).contains("GFMElementTypes.TABLE")
        assertThat(markdownSource).contains("standaloneInlineCode(node, state.content)")
        assertThat(markdownSource).contains("node.containsWideTableDescendant()")
        assertThat(markdownSource).contains("nestedTableMinimumWidth(")
        assertThat(markdownSource).contains("WideAwareContainer(")
        assertThat(markdownSource).doesNotContain("findStandaloneInlineCode")
        assertThat(markdownSource).doesNotContain("splitWideMarkdownSegments")
        assertThat(markdownSource).doesNotContain("WideMarkdownSegment")
        assertThat(hostSource).contains("BoxWithConstraints")
        assertThat(hostSource).contains("maxOf(viewportWidth, contentWidth)")
        assertThat(hostSource).contains("rememberTextMeasurer()")
        assertThat(hostSource).contains("widestLogicalLineWidthPx(text)")
        assertThat(hostSource).doesNotContain("maxByOrNull(String::length)")
        assertThat(hostSource).contains("horizontalScroll(scrollState)")
        assertThat(hostSource).contains("UiTags.MARKDOWN_WIDE_CONTENT")
    }

    @Test
    fun `顶层表格由成功回调外部宽内容宿主接管且保留表格组件`() {
        val markdownSource = source("src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt")
        val tableSource = source("src/main/java/com/promenar/nexara/ui/renderer/TableWidget.kt")
        val successSource = markdownSource
            .substringAfter("success = { state, resolvedComponents, successModifier ->")
            .substringBefore("private fun WideContentHost")

        assertThat(successSource).contains("GFMElementTypes.TABLE")
        assertThat(successSource).contains("WideContentHost(")
        assertThat(successSource).contains("NexaraTableWidget(")
        assertThat(successSource).contains("table.requiredWidth")
        assertThat(successSource).contains("MarkdownElement(node, resolvedComponents, state.content)")
        assertThat(tableSource).contains("BoxWithConstraints")
        assertThat(tableSource).contains("val fixedColumns = maxWidth <= table.requiredWidth")
    }
}
