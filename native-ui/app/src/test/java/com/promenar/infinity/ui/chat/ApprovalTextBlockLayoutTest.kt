package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApprovalTextBlockLayoutTest {
    private val collapsedLines = ApprovalTextBlockLayout.COLLAPSED_LINES

    @Test
    fun `short text does not need expand entry`() {
        val text = (1..collapsedLines).joinToString("\n") { "line $it" }

        assertThat(ApprovalTextBlockLayout.hasExplicitOverflow(text)).isFalse()
        assertThat(ApprovalTextBlockLayout.collapsedText(text)).isEqualTo(text)
    }

    @Test
    fun `text longer than collapsed lines overflows and collapsed render stays small`() {
        val text = (1..80).joinToString("\n") { "line $it" }

        assertThat(ApprovalTextBlockLayout.hasExplicitOverflow(text)).isTrue()
        // 多保留一行用于触发省略号，其余行不参与收起态排版。
        assertThat(ApprovalTextBlockLayout.collapsedText(text).lines()).hasSize(collapsedLines + 1)
    }

    @Test
    fun `expanded text is the complete original without truncation`() {
        val text = (1..80).joinToString("\n") { "line $it " + "x".repeat(it * 10) }

        val expanded = ApprovalTextBlockLayout.expandedText(text)

        assertThat(expanded).isEqualTo(text)
        assertThat(expanded.lines()).hasSize(80)
        assertThat(expanded.lines().last()).endsWith("x".repeat(800))
        assertThat(expanded).doesNotContain("… (+")
    }

    @Test
    fun `single very long line counts as overflow and is trimmed only when collapsed`() {
        val text = "y".repeat(5_000)

        assertThat(ApprovalTextBlockLayout.hasExplicitOverflow(text)).isTrue()
        assertThat(ApprovalTextBlockLayout.collapsedText(text).length).isLessThan(text.length)
        assertThat(ApprovalTextBlockLayout.expandedText(text)).isEqualTo(text)
    }
}
