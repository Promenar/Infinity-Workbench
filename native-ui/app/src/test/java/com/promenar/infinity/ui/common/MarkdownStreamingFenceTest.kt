package com.promenar.infinity.ui.common

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownStreamingFenceTest {
    @Test
    fun `带语言围栏当前行逐字符交给官方代码提取路径`() {
        assertVisibleFence("```kotlin\nval first = 1\nval second = 2\n```", "val first = 1\nval sec")
    }

    @Test
    fun `无语言围栏当前行逐字符交给官方代码提取路径`() {
        assertVisibleFence("```\nfirst\nsecond\n```", "first\nsec")
    }

    @Test
    fun `波浪号围栏当前行逐字符交给官方代码提取路径`() {
        assertVisibleFence("~~~text\nfirst\nsecond\n~~~", "first\nsec")
    }

    private fun assertVisibleFence(content: String, expected: String) {
        val fence = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content).children
            .single { it.type == MarkdownElementTypes.CODE_FENCE }
        val end = content.indexOf(expected) + expected.length
        val visible = visibleMarkdownNode(fence, end)
        assertEquals(expected, officialFenceText(content, visible))
    }

    /** 0.41.0 MarkdownCodeFence 以倒数第二个子节点作为正文终点，要求裁剪树保持闭合项契约。 */
    private fun officialFenceText(content: String, node: ASTNode): String {
        val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(content)?.toString()
        if (node.children.size < 3) return ""
        val start = node.children[2].startOffset
        val minimum = if (language != null && node.children.size > 3) 3 else 2
        val end = node.children[(node.children.size - 2).coerceAtLeast(minimum)].endOffset
        return content.subSequence(start, end).toString().replaceIndent()
    }
}
