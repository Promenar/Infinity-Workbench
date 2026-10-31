package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class ThinkingPreviewMotionTest {
    @get:Rule val rule = createComposeRule()

    /** 引擎状态在原地更新；预览层必须每帧重绘，而不是因参数未变被跳过、停在挂载时的文字。 */
    @Test
    fun collapsedPreviewKeepsMovingWhileReasoningStreams() {
        var reasoning by mutableStateOf("Both /home/session and /workspace are mounted. ")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            InfinityTheme {
                Row(Modifier.width(320.dp)) { ThinkingPreview(reasoning, ThinkingRateHolder()) }
            }
        }
        val seen = mutableSetOf<String>()
        // 每帧约 8 个字符，约合 480 字/秒，对应真机上百 token/秒的思考输出。
        repeat(120) { frame ->
            reasoning += "step$frame ok "
            rule.mainClock.advanceTimeByFrame()
            seen += previewText()
        }
        assertThat(seen.size).isAtLeast(20)
    }

    private fun previewText(): String {
        val texts = mutableListOf<String>()
        fun collect(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.children.forEach(::collect)
        }
        collect(rule.onNodeWithTag("CHAT_THINKING_PREVIEW", useUnmergedTree = true).fetchSemanticsNode())
        return texts.joinToString("|")
    }
}
