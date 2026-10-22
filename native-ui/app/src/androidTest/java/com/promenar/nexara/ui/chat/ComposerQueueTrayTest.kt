package com.promenar.nexara.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 生成中排队：发送键与次级停止键的分工，以及排队托盘的插队、编辑、移到最前、删除、继续与折叠。 */
class ComposerQueueTrayTest {
    @get:Rule
    val rule = createComposeRule()

    private val session = Session(id = "session-queue", agentId = "agent", title = "Queue", modelId = "provider/model")
    private val generating = ChatUiState(session = session, isGenerating = true, status = GenerationStatus.RECEIVING)

    @Test
    fun generatingWithDraft_primaryQueuesAndSecondaryStops() {
        var sent: String? = null
        var stopped = false
        render(
            uiState = generating,
            inputText = "下一条",
            actions = ChatScreenActions(onSend = { text, _ -> sent = text }, onStop = { stopped = true }),
        )

        rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .assertContentDescriptionEquals(resource(R.string.chat_queue_send_cd))
            .performClick()
        rule.runOnIdle { assertThat(sent).isEqualTo("下一条") }
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_STOP)
            .assertContentDescriptionEquals(resource(R.string.chat_queue_stop_cd))
            .performClick()
        rule.runOnIdle { assertThat(stopped).isTrue() }
        // 生成中可以继续添加附件，随排队消息一起发出。
        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).assertIsEnabled()
    }

    @Test
    fun generatingWithoutDraft_keepsSingleStopButton() {
        var input by mutableStateOf("")
        render(uiState = generating, inputTextProvider = { input })

        rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .assertContentDescriptionEquals(resource(R.string.chat_cd_stop))
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_STOP).assertDoesNotExist()
        rule.runOnIdle { input = "写好了" }
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_STOP).assertIsDisplayed()
        rule.runOnIdle { input = "" }
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_STOP).assertDoesNotExist()
    }

    @Test
    fun trayActions_reportTheTappedItem() {
        val calls = mutableListOf<String>()
        render(
            uiState = generating,
            queue = ComposerQueueUiState(listOf(QueuedMessageUi("q1", "第一条"), QueuedMessageUi("q2", "第二条"))),
            actions = ChatScreenActions(
                onSteerQueued = { calls += "steer:$it" },
                onEditQueued = { calls += "edit:$it" },
                onMoveQueuedToFront = { calls += "front:$it" },
                onRemoveQueued = { calls += "remove:$it" },
            ),
        )

        rule.onNodeWithTag(UiTags.CHAT_QUEUE_TRAY).assertIsDisplayed()
        inItem("q2", UiTags.CHAT_QUEUE_STEER).performClick()
        inItem("q2", UiTags.CHAT_QUEUE_MORE).performClick()
        rule.onNodeWithText(resource(R.string.chat_queue_move_front)).performClick()
        inItem("q1", UiTags.CHAT_QUEUE_MORE).performClick()
        // 队首没有“移到最前”。
        rule.onNodeWithText(resource(R.string.chat_queue_move_front)).assertDoesNotExist()
        rule.onNodeWithText(resource(R.string.chat_queue_edit)).performClick()
        inItem("q1", UiTags.CHAT_QUEUE_MORE).performClick()
        rule.onNodeWithText(resource(R.string.chat_queue_remove)).performClick()
        rule.runOnIdle { assertThat(calls).containsExactly("steer:q2", "front:q2", "edit:q1", "remove:q1").inOrder() }
    }

    @Test
    fun editIsDisabledWhileTheComposerHoldsADraft() {
        var edited = false
        render(
            uiState = generating,
            inputText = "正在写",
            queue = ComposerQueueUiState(listOf(QueuedMessageUi("q1", "排队的"))),
            actions = ChatScreenActions(onEditQueued = { edited = true }),
        )

        inItem("q1", UiTags.CHAT_QUEUE_MORE).performClick()
        rule.onNodeWithText(resource(R.string.chat_queue_edit)).assertIsNotEnabled().performClick()
        rule.runOnIdle { assertThat(edited).isFalse() }
    }

    @Test
    fun steeringItem_showsPendingLabelInsteadOfButton() {
        render(
            uiState = generating,
            queue = ComposerQueueUiState(listOf(QueuedMessageUi("q1", "改方向", steering = true))),
        )

        rule.onNodeWithText(resource(R.string.chat_queue_steering)).assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.CHAT_QUEUE_STEER).assertCountEquals(0)
    }

    @Test
    fun pausedQueue_explainsReasonAndResumes() {
        var resumed = false
        render(
            uiState = ChatUiState(session = session),
            queue = ComposerQueueUiState(
                items = listOf(QueuedMessageUi("q1", "等着的")),
                pauseReason = ComposerQueuePauseReason.STOPPED,
            ),
            actions = ChatScreenActions(onResumeQueue = { resumed = true }),
        )

        rule.onNodeWithText(resource(R.string.chat_queue_paused_stopped)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_RESUME).performClick()
        rule.runOnIdle { assertThat(resumed).isTrue() }
    }

    @Test
    fun longQueue_collapsesToThreeAndExpands() {
        render(
            uiState = generating,
            queue = ComposerQueueUiState((1..5).map { QueuedMessageUi("q$it", "第 $it 条") }),
        )

        rule.onNodeWithTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + "q3").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + "q4").assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_EXPAND).performClick()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + "q5").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_EXPAND).performClick()
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + "q4").assertDoesNotExist()
    }

    @Test
    fun emptyQueue_rendersNoTray() {
        render(uiState = generating)
        rule.onNodeWithTag(UiTags.CHAT_QUEUE_TRAY).assertDoesNotExist()
    }

    private fun inItem(id: String, tag: String) =
        rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + id)))

    private fun render(
        uiState: ChatUiState,
        inputText: String = "",
        inputTextProvider: (() -> String)? = null,
        queue: ComposerQueueUiState = ComposerQueueUiState(),
        actions: ChatScreenActions = ChatScreenActions(),
    ) {
        rule.setContent {
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = uiState,
                        inputText = inputTextProvider?.invoke() ?: inputText,
                        composerQueue = queue,
                    ),
                    actions = actions,
                )
            }
        }
    }

    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
