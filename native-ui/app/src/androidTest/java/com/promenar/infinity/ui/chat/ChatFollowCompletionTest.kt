package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class ChatFollowCompletionTest {
    @get:Rule val rule = createComposeRule()
    private var generating by mutableStateOf(true)
    private var body by mutableStateOf((1..30).joinToString("\n\n") { "回复第 $it 段，核对正文末尾与输入区之间的距离。" })

    @Test
    fun completionKeepsLastActionButtonAboveComposer() {
        render()
        waitForStreamingTail()
        rule.runOnIdle { generating = false }
        assertLastActionAboveComposer()
    }

    @Test
    fun completionDoesNotMoveListAfterDeliberateUpScroll() {
        render()
        waitForStreamingTail()
        val following = scrollPosition(UiTags.CHAT_STATE_GENERATING)
        rule.onNodeWithTag(UiTags.CHAT_STATE_GENERATING).performTouchInput { swipeDown() }
        val before = scrollPosition(UiTags.CHAT_STATE_GENERATING)
        assertThat(before).isLessThan(following)
        rule.runOnIdle { generating = false }
        assertThat(scrollPosition(UiTags.CHAT_STATE_READY)).isWithin(0.01f).of(before)
    }

    @Test
    fun asynchronousLongHistoryKeepsFinalActionAboveComposer() {
        generating = false
        body = (1..240).joinToString("\n\n") { "第 $it 段：较长的历史回复包含 **重点** 与行内 `code`，解析完成后仍可看到最后的操作行。" }
        assertThat(body.length).isGreaterThan(12_000)
        render()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("CHAT_ASSISTANT_BODY", useUnmergedTree = true)
                .fetchSemanticsNodes().lastOrNull()?.size?.height?.let { it > 1_000 } == true
        }
        assertLastActionAboveComposer()
    }

    private fun waitForStreamingTail() {
        rule.waitUntil(15_000) {
            val bodyNode = rule.onAllNodesWithTag("CHAT_ASSISTANT_BODY", useUnmergedTree = true)
                .fetchSemanticsNodes().lastOrNull()
            val composerNode = rule.onAllNodesWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNodes().lastOrNull()
            bodyNode != null && composerNode != null &&
                bodyNode.size.height / bodyNode.layoutInfo.density.density > 600f &&
                bodyNode.boundsInRoot.height > 0f && bodyNode.boundsInRoot.bottom <= composerNode.boundsInRoot.top + 1f
        }
    }

    private fun scrollPosition(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertLastActionAboveComposer() {
        rule.waitForIdle()
        val last = rule.onAllNodesWithTag("CHAT_ASSISTANT_MORE", useUnmergedTree = true).fetchSemanticsNodes().last()
        val composer = rule.onNodeWithTag(UiTags.CHAT_COMPOSER).fetchSemanticsNode().boundsInRoot
        assertThat(last.boundsInRoot.bottom).isAtMost(composer.top + 1f)
        assertThat(last.boundsInRoot.top).isAtLeast(0f)
        rule.onAllNodesWithTag("CHAT_ASSISTANT_MORE", useUnmergedTree = true).let { it[it.fetchSemanticsNodes().lastIndex] }.assertIsDisplayed()
    }

    private fun render() = rule.setContent {
        InfinityTheme {
            Box(Modifier.width(411.dp).height(640.dp)) {
                ChatScreenContent(
                    state = ChatScreenState(uiState = ChatUiState(
                        session = Session(id = "follow", agentId = "agent", title = "尾部跟随", modelId = "provider/model"),
                        messages = listOf(Message("user", MessageRole.USER, "请说明。"), Message("assistant", MessageRole.ASSISTANT, body)),
                        isGenerating = generating,
                        streamingContent = if (generating) body else "",
                        status = if (generating) GenerationStatus.RECEIVING else GenerationStatus.IDLE,
                    )),
                    actions = ChatScreenActions(),
                )
            }
        }
    }
}
