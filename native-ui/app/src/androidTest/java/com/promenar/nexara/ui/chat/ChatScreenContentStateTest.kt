package com.promenar.nexara.ui.chat

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ChatScreenContentStateTest {
    @get:Rule
    val rule = createComposeRule()

    private val session = Session(
        id = "session-e2e",
        agentId = "agent-e2e",
        title = "E2E Chat",
        modelId = "provider/model-e2e",
    )

    @Test
    fun emptyState_rendersRealConversationSurfaceAndInput() {
        render(ChatUiState(session = session))

        rule.onNodeWithTag(UiTags.CHAT_STATE_EMPTY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(100.dp)
        val titles = rule.onAllNodesWithText("E2E Chat")
        titles.assertCountEquals(2)
        titles[0].assertIsDisplayed()
        titles[1].assertIsDisplayed()
        rule.onNodeWithText("provider/model-e2e").assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.chat_input_placeholder_default)).assertIsDisplayed()
    }

    @Test
    fun loadingState_rendersSkeletonOnRealConversationSurface() {
        render(ChatUiState(session = session, isLoading = true))

        rule.onNodeWithTag(UiTags.CHAT_STATE_LOADING)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(100.dp)
        rule.onNodeWithTag(UiTags.CHAT_LOADING_SKELETON).assertIsDisplayed()
    }

    @Test
    fun generatingState_exposesRealStopAction() {
        var stopped = false
        render(
            uiState = ChatUiState(
                session = session,
                isGenerating = true,
                status = GenerationStatus.THINKING,
            ),
            actions = ChatScreenActions(onStop = { stopped = true }),
        )

        rule.onNodeWithTag(UiTags.CHAT_STATE_GENERATING).assertHeightIsAtLeast(100.dp)
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .assertContentDescriptionEquals(resource(R.string.chat_cd_stop))
            .performClick()
        rule.runOnIdle { assertThat(stopped).isTrue() }
    }

    @Test
    fun approvalState_rendersToolDetailsAndDispatchesApprove() {
        var approved = false
        render(
            uiState = ChatUiState(
                session = session,
                approvalRequest = ApprovalRequest(
                    toolName = "write_file",
                    args = "{\"path\":\"notes.md\"}",
                    reason = "Update workspace",
                ),
            ),
            actions = ChatScreenActions(onApprove = { approved = true }),
        )

        rule.onNodeWithTag(UiTags.CHAT_STATE_APPROVAL).assertHeightIsAtLeast(100.dp)
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onNodeWithText("Update workspace", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).performClick()
        rule.runOnIdle { assertThat(approved).isTrue() }
    }

    @Test
    fun errorState_rendersRealErrorActionSemantics() {
        render(
            ChatUiState(
                session = session,
                status = GenerationStatus.ERROR,
                error = "Provider unavailable",
            ),
        )

        rule.onNodeWithTag(UiTags.CHAT_STATE_ERROR).assertHeightIsAtLeast(100.dp)
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .assertContentDescriptionEquals(resource(R.string.chat_status_error))
            .assertIsDisplayed()
    }

    private fun render(
        uiState: ChatUiState,
        actions: ChatScreenActions = ChatScreenActions(),
    ) {
        rule.setContent {
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(uiState = uiState),
                    actions = actions,
                )
            }
        }
    }

    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
