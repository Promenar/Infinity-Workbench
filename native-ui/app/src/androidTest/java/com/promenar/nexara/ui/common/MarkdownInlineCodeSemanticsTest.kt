package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 行内代码两侧的内边距占位不得以 U+FFFD 进入无障碍文本，读屏会把它读成替换字符。 */
class MarkdownInlineCodeSemanticsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun inlineCodePaddingIsNotExposedAsReplacementCharacter() {
        rule.setContent { NexaraTheme { Box(Modifier.width(360.dp)) { MarkdownText("在 `termux` 中运行 `whoami` 即可。", fontSize = 15) } } }
        rule.waitForIdle()

        val texts = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            buildList {
                fun visit(node: androidx.compose.ui.semantics.SemanticsNode) {
                    node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
                    node.children.forEach(::visit)
                }
                visit(root)
            }
        }
        val paragraph = texts.single { "termux" in it }
        assertThat(paragraph).doesNotContain("�")
        assertThat(paragraph.replace(' ', ' ').replace(Regex(" +"), " ")).isEqualTo("在 termux 中运行 whoami 即可。")
    }
}
