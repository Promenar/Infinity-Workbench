package com.promenar.infinity.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReadingLayoutTest {
    @Test
    fun `段落标题和媒体块按边界节奏排版`() {
        val paragraph = MarkdownElementTypes.PARAGRAPH
        val heading = MarkdownElementTypes.ATX_2
        assertEquals(16f, markdownReadingGap(paragraph, paragraph).value, 0.001f)
        assertEquals(24f, markdownReadingGap(paragraph, heading).value, 0.001f)
        assertEquals(10f, markdownReadingGap(heading, paragraph).value, 0.001f)
        for (media in listOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.BLOCK_QUOTE, GFMElementTypes.TABLE)) {
            assertEquals(16f, markdownReadingGap(paragraph, media).value, 0.001f)
            assertEquals(16f, markdownReadingGap(media, paragraph).value, 0.001f)
        }
    }

    @Test
    fun `列表首末项内边距包含在与段落或标题后的十二点间距中`() {
        val paragraph = MarkdownElementTypes.PARAGRAPH
        for (list in listOf(MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.UNORDERED_LIST)) {
            assertEquals(12f, markdownReadingGap(paragraph, list).value + 5f, 0.001f)
            assertEquals(12f, markdownReadingGap(list, paragraph).value + 5f, 0.001f)
            assertEquals(12f, markdownReadingGap(MarkdownElementTypes.ATX_2, list).value + 5f, 0.001f)
            assertEquals(24f, markdownReadingGap(list, MarkdownElementTypes.ATX_2).value + 5f, 0.001f)
        }
    }

    @Test
    fun `帧裁剪复用完整块并隐藏未到达节点`() {
        val content = "首段\n\n第二段\n\n第三段"
        val tree = parse(content)
        val first = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val visible = visibleMarkdownNode(tree, content.indexOf("第二段") + 1)
        assertSame(first, visible.children.first { it.type == MarkdownElementTypes.PARAGRAPH })
        assertEquals(2, visible.children.count { it.type == MarkdownElementTypes.PARAGRAPH })
        assertTrue(visible.children.all { it.endOffset <= visible.endOffset })
        assertSame(tree, visibleMarkdownNode(tree, content.length))
    }

    @Test
    fun `部分行内代码保留可见正文与零长闭合节点`() {
        val content = "前文 `code` 后文"
        val end = content.indexOf("code") + 2
        val tree = visibleMarkdownNode(parse(content), end)
        val code = descendants(tree).single { it.type == MarkdownElementTypes.CODE_SPAN }
        assertEquals(MarkdownTokenTypes.BACKTICK, code.children.last().type)
        assertEquals(end, code.children.last().startOffset)
        assertEquals(end, code.children.last().endOffset)
        assertEquals("co", code.children.drop(1).dropLast(1).joinToString("") { content.substring(it.startOffset, it.endOffset) })
    }

    private fun parse(content: String) = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)

    private fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
}
