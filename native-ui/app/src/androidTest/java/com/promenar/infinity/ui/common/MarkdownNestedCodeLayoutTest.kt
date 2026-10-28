package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class MarkdownNestedCodeLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val line = "println(\"${"wide code ".repeat(20)}可见尾部\")"

    @Test
    fun quotedCodeKeepsHorizontalTailReachable() = checkCode("> ```kotlin\n> $line\n> ```")

    @Test
    fun listCodeKeepsHorizontalTailReachable() = checkCode("- 代码\n\n  ```kotlin\n  $line\n  ```")

    private fun checkCode(source: String) {
        rule.setContent { InfinityTheme { Box(Modifier.width(360.dp)) { MarkdownText(source, fontSize = 15) } } }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(UiTags.MARKDOWN_WIDE_CONTENT).fetchSemanticsNodes().size == 1 &&
                rule.onAllNodesWithTag("CHAT_MARKDOWN_CODE_TEXT", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val host = rule.onNodeWithTag(UiTags.MARKDOWN_WIDE_CONTENT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val initial = host.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(initial.maxValue()).isGreaterThan(0f)
        host.performSemanticsAction(SemanticsActions.ScrollBy) { assertThat(it(100_000f, 0f)).isTrue() }
        val final = host.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(final.value()).isAtLeast(final.maxValue() - 1f)
        val text = rule.onNodeWithTag("CHAT_MARKDOWN_CODE_TEXT", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val tailIndex = layout.layoutInput.text.text.indexOf("可见尾部")
        assertThat(tailIndex).isAtLeast(0)
        val tail = layout.getBoundingBox(tailIndex + 3)
        val textX = text.fetchSemanticsNode().positionInRoot.x
        val viewport = host.fetchSemanticsNode().boundsInRoot
        assertThat(textX + tail.left).isAtLeast(viewport.left - 1f)
        assertThat(textX + tail.right).isAtMost(viewport.right + 1f)
    }
}
