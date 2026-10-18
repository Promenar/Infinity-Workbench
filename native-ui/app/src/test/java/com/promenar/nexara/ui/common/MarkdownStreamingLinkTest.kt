package com.promenar.nexara.ui.common

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamingLinkTest {
    private val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), markdownAnnotator())

    @Test
    fun `显式链接目的地逐字放出期间标签可见且没有半截点击目标`() {
        val content = "前 [查看文档](https://example.com/guide#section) 后"
        val tree = parse(content)
        val destination = descendants(tree).single { it.type == MarkdownElementTypes.LINK_DESTINATION }
        for (end in destination.startOffset until destination.endOffset) {
            val annotated = annotate(content, visibleMarkdownNode(tree, end))
            assertEquals("前 查看文档", annotated.text)
            val links = annotated.getLinkAnnotations(0, annotated.length)
            assertTrue("可见终点=$end，不应建立半截点击目标：$links", links.isEmpty())
        }
    }

    @Test
    fun `显式链接目的地完整后官方注解建立完整URL`() {
        val content = "前 [查看文档](https://example.com/guide#section) 后"
        val tree = parse(content)
        val destination = descendants(tree).single { it.type == MarkdownElementTypes.LINK_DESTINATION }
        val annotated = annotate(content, visibleMarkdownNode(tree, destination.endOffset))
        assertEquals("前 查看文档", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/guide#section", link.url)
    }

    @Test
    fun `普通自动链接保留原有随可见文本变化的目标`() {
        val content = "前 https://example.com/guide 后"
        val end = content.indexOf("guide") + 2
        val annotated = annotate(content, visibleMarkdownNode(parse(content), end))
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/gu", link.url)
    }

    private fun annotate(content: String, tree: ASTNode) = content.buildMarkdownAnnotatedString(
        tree.children.single { it.type == MarkdownElementTypes.PARAGRAPH },
        TextStyle.Default,
        settings,
    )

    private fun parse(content: String) = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)

    private fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
}
