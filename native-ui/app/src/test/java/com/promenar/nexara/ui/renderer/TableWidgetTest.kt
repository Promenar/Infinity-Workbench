package com.promenar.nexara.ui.renderer

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Test

class TableWidgetTest {
    @Test
    fun `parses header and body cell semantics from the GFM table AST`() {
        val markdown = """
            | Model | Context | Status |
            |---|---:|:---:|
            | DeepSeek V4 | 64K | Ready |
            | MiniMax M3 | 128K | Preview |
        """.trimIndent()
        val tableNode = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .single { it.type == GFMElementTypes.TABLE }

        val table = parseMarkdownTable(markdown, tableNode)

        assertThat(table).isNotNull()
        assertThat(table!!.headerCells).containsExactly("Model", "Context", "Status").inOrder()
        assertThat(table.rows).containsExactly(
            listOf("DeepSeek V4", "64K", "Ready"),
            listOf("MiniMax M3", "128K", "Preview"),
        ).inOrder()
        assertThat(table.requiredWidth).isEqualTo(360.dp)
    }

    @Test
    fun `rejects non table AST nodes`() {
        val markdown = "ordinary paragraph"
        val paragraph = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .single()

        assertThat(parseMarkdownTable(markdown, paragraph)).isNull()
    }
}
