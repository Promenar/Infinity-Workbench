package com.promenar.infinity.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamAstBoundaryTest {
    @Test
    fun `图片链接未完整可见时不把半截URL交给图片组件`() {
        val content = "看图 ![说明](https://example.com/image.png) 完成"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val end = content.indexOf("image.png") + 3
        assertTrue(descendants(visibleMarkdownNode(tree, end)).none { it.type == MarkdownElementTypes.IMAGE })
    }

    @Test
    fun `图片链接完整可见时保持原始完整地址`() {
        val content = "看图 ![说明](https://example.com/image.png) 完成"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val end = content.indexOf(")") + 1
        val destination = descendants(visibleMarkdownNode(tree, end)).single { it.type == MarkdownElementTypes.LINK_DESTINATION }
        assertEquals("https://example.com/image.png", content.substring(destination.startOffset, destination.endOffset))
    }

    private fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
}
