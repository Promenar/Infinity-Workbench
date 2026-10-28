package com.promenar.infinity.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownResourceFixtureTest {
    @Test
    fun 中英文示例保留三层列表与原始缩进() {
        for (text in listOf(MARKDOWN_BATCH2_EN, MARKDOWN_BATCH2_ZH)) {
            assertTrue(text.contains("\n   - "))
            assertTrue(text.contains("\n      - "))
            val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(prepareMarkdownForRendering(text))
            assertEquals(3, listDepth(tree, 0))
        }
    }

    private fun listDepth(node: ASTNode, depth: Int): Int {
        val next = depth + if (node.type == MarkdownElementTypes.ORDERED_LIST || node.type == MarkdownElementTypes.UNORDERED_LIST) 1 else 0
        return maxOf(next, node.children.maxOfOrNull { listDepth(it, next) } ?: next)
    }
}
