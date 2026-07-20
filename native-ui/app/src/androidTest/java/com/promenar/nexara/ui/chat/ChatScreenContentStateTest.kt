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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.model.Message
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlin.math.abs
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
        render(
            uiState = ChatUiState(session = session),
            modelDisplayNames = mapOf("provider/model-e2e" to "Model E2E"),
        )

        rule.onNodeWithTag(UiTags.CHAT_STATE_EMPTY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(100.dp)
        val titles = rule.onAllNodesWithText("E2E Chat")
        titles.assertCountEquals(2)
        titles[0].assertIsDisplayed()
        titles[1].assertIsDisplayed()
        rule.onNodeWithText("Model E2E").assertIsDisplayed()
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

    @Test
    fun assistantMetadata_shortModelName_layout_keepsLeftToRightAndBottomAlignment() {
        val displayName = "Nova Chat"
        val modelId = "provider/model-e2e"
        val createdAt = 1_700_000_000_000L

        render(
            uiState = ChatUiState(
                session = session.copy(
                    options = SessionOptions(fontSize = 13),
                ),
                messages = listOf(
                    Message(
                        id = "assistant-metadata-short",
                        role = MessageRole.ASSISTANT,
                        content = "这是一个短模型元信息场景。",
                        modelId = modelId,
                        createdAt = createdAt,
                    ),
                ),
            ),
            modelDisplayNames = mapOf(modelId to displayName),
        )

        val modelMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA)
        val timeMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA)
        val readyRoot = rule.onNodeWithTag(UiTags.CHAT_STATE_READY)

        modelMetadata.assertIsDisplayed()
        timeMetadata.assertIsDisplayed()

        val modelBounds = modelMetadata.fetchSemanticsNode().boundsInRoot
        val timeBounds = timeMetadata.fetchSemanticsNode().boundsInRoot
        val readyBounds = readyRoot.fetchSemanticsNode().boundsInRoot

        assertThat(modelBounds.left).isAtLeast(readyBounds.left)
        assertThat(modelBounds.left).isLessThan(timeBounds.left)
        assertThat(modelBounds.right).isAtMost(timeBounds.left)

        val tolerancePx = with(rule.density) { 3.dp.toPx() }
        assertThat(abs(modelBounds.bottom - timeBounds.bottom)).isAtMost(tolerancePx)

        val centerX = readyBounds.left + (readyBounds.width / 2f)
        assertThat(timeBounds.right).isLessThan(centerX)
    }

    @Test
    fun assistantMetadata_longModelName_keepsTimeVisibleAndSingleLine() {
        val modelId = "provider/model-e2e"
        val displayName = "超长模型展示名称：用于边界回归的长名称场景，仍需保证时间戳可见且不换行"
        val createdAt = 1_700_000_123_000L

        render(
            uiState = ChatUiState(
                session = session.copy(
                    options = SessionOptions(fontSize = 13),
                ),
                messages = listOf(
                    Message(
                        id = "assistant-metadata-long",
                        role = MessageRole.ASSISTANT,
                        content = "超长模型名场景用于验证单行元信息渲染。",
                        modelId = modelId,
                        createdAt = createdAt,
                    ),
                ),
            ),
            modelDisplayNames = mapOf(modelId to displayName),
        )

        val modelMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA)
        val timeMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA)
        val readyBounds = rule.onNodeWithTag(UiTags.CHAT_STATE_READY).fetchSemanticsNode().boundsInRoot

        modelMetadata.assertIsDisplayed()
        timeMetadata.assertIsDisplayed()

        val modelBounds = modelMetadata.fetchSemanticsNode().boundsInRoot
        val timeBounds = timeMetadata.fetchSemanticsNode().boundsInRoot

        val tolerancePx = with(rule.density) { 3.dp.toPx() }

        assertThat(modelBounds.width).isGreaterThan(0)
        assertThat(modelBounds.right).isAtMost(timeBounds.left)
        assertThat(abs(modelBounds.bottom - timeBounds.bottom)).isAtMost(tolerancePx)
        assertThat(modelBounds.height).isAtMost(timeBounds.height + tolerancePx)

        val centerX = readyBounds.left + (readyBounds.width / 2f)
        assertThat(timeBounds.right).isLessThan(centerX)
    }

    @Test
    fun documentAttachments_renderRemovePickAndBranchActions() {
        val document = MessageDocumentAttachment(
            id = "document-e2e",
            name = "very-long-reference-document-name-for-layout-validation.markdown",
            mimeType = "text/markdown",
            content = "full context",
            sizeBytes = 12_345L,
            sha256 = "document-hash",
            estimatedTokens = 4,
        )
        var removedId: String? = null
        var pickedDocument = false
        var branchedMessageId: String? = null
        render(
            uiState = ChatUiState(
                session = session,
                messages = listOf(
                    Message(
                        id = "branch-target",
                        role = MessageRole.USER,
                        content = "Branch target message",
                        userDocuments = listOf(document),
                        createdAt = 1_700_000_000_000L,
                    ),
                ),
            ),
            draftDocuments = listOf(document),
            actions = ChatScreenActions(
                onRemoveDocument = { removedId = it },
                onPickDocuments = { pickedDocument = true },
                onBranchMessage = { branchedMessageId = it },
            ),
        )

        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_DOCUMENTS, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithTag(UiTags.chatDocumentChip(document.id)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(removedId).isEqualTo(document.id) }

        rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT).assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(pickedDocument).isTrue() }

        rule.onNodeWithText("Branch target message").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.CHAT_MESSAGE_BRANCH).assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(branchedMessageId).isEqualTo("branch-target") }
    }

    private fun render(
        uiState: ChatUiState,
        actions: ChatScreenActions = ChatScreenActions(),
        modelDisplayNames: Map<String, String> = emptyMap(),
        draftDocuments: List<MessageDocumentAttachment> = emptyList(),
    ) {
        rule.setContent {
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = uiState,
                        modelDisplayNames = modelDisplayNames,
                        draftDocuments = draftDocuments,
                    ),
                    actions = actions,
                )
            }
        }
    }

    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
