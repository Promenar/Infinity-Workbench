package com.promenar.nexara.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.promenar.nexara.data.model.Message
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.ApprovalCallIdentity
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.TaskStep
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
        titles.assertCountEquals(1)
        titles[0].assertIsDisplayed()
        rule.onNodeWithText("Model E2E").assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.chat_input_placeholder_default)).assertIsDisplayed()
        // 空会话显示粒子字标与提示语。
        rule.onNodeWithTag(UiTags.CHAT_EMPTY_WORDMARK).assertExists()
        rule.onNodeWithText(resource(R.string.chat_empty_prompt)).assertIsDisplayed()
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
        // 生成中：浮岛顶部展开活动栏，不显示空会话字标，输入框仍可编辑。
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_PROGRESS).assertExists()
        rule.onNodeWithTag(UiTags.CHAT_EMPTY_WORDMARK).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_INPUT).assertIsEnabled()
        rule.onNodeWithText(resource(R.string.chat_input_placeholder_generating)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .assertContentDescriptionEquals(resource(R.string.chat_cd_stop))
            .performClick()
        rule.runOnIdle { assertThat(stopped).isTrue() }
    }

    @Test
    fun waitingForFirstToken_showsGenerationDotsUntilContentArrives() {
        val question = Message("user-1", MessageRole.USER, "写一首短诗", createdAt = 1L)
        var uiState by mutableStateOf(
            ChatUiState(
                session = session,
                messages = listOf(question, Message("assistant-1", MessageRole.ASSISTANT, "", createdAt = 2L)),
                isGenerating = true,
                status = GenerationStatus.THINKING,
            ),
        )
        rule.setContent {
            NexaraTheme {
                ChatScreenContent(state = ChatScreenState(uiState = uiState), actions = ChatScreenActions())
            }
        }

        // 首字到达前显示三点波浪；无限动画不阻塞测试空闲，读屏报“正在生成回复”。
        rule.onNodeWithTag(UiTags.CHAT_GENERATING_INDICATOR, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription(resource(R.string.chat_cd_generating_response), useUnmergedTree = true).assertExists()

        rule.runOnIdle {
            uiState = uiState.copy(messages = listOf(question, Message("assistant-1", MessageRole.ASSISTANT, "春风", createdAt = 2L)))
        }
        rule.onNodeWithTag(UiTags.CHAT_GENERATING_INDICATOR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun pendingTask_rendersThirdCapsuleAndDispatchesManualActions() {
        var continued = false
        var completed = false
        val taskPanel = taskPanelUiState(
            listOf(
                TaskStep(
                    id = "plan",
                    title = "发布检查",
                    children = listOf(
                        TaskStep(id = "done", title = "安全检查", status = "done"),
                        TaskStep(id = "pending", title = "视觉回归", status = "doing", sortOrder = 1),
                    ),
                ),
            ),
            isGenerating = false,
        )!!

        render(
            uiState = ChatUiState(session = session),
            taskPanel = taskPanel,
            actions = ChatScreenActions(
                onContinueTask = { continued = true },
                onCompleteTask = { completed = true },
            ),
        )

        // 计划条默认显示在输入框上方；关闭后只留状态栏入口，点入口重新打开。
        rule.onNodeWithTag(UiTags.CHAT_TASK_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CAPSULE).assertDoesNotExist()
        assertMinimumTouchTarget(UiTags.CHAT_TASK_CLOSE)
        rule.onNodeWithTag(UiTags.CHAT_TASK_CLOSE)
            .assertContentDescriptionEquals(resource(R.string.chat_task_hide))
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CARD).assertDoesNotExist()
        assertMinimumTouchTarget(UiTags.CHAT_TASK_CAPSULE)
        rule.onNodeWithTag(UiTags.CHAT_TASK_CAPSULE)
            .assertContentDescriptionEquals(resource(R.string.chat_task_pending_accessibility, 1))
            .assertHeightIsAtLeast(28.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CARD).assertIsDisplayed()
        assertMinimumTouchTarget(UiTags.CHAT_TASK_PANEL_HEADER)
        rule.onNodeWithTag(UiTags.CHAT_TASK_PANEL_HEADER).performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_COMPLETE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CONTINUE).performClick()
        rule.runOnIdle {
            assertThat(completed).isTrue()
            assertThat(continued).isTrue()
        }
    }

    @Test
    fun generatingTask_disablesManualActions() {
        val taskPanel = taskPanelUiState(
            listOf(TaskStep(id = "plan", title = "发布检查", status = "doing")),
            isGenerating = true,
        )!!

        render(
            uiState = ChatUiState(
                session = session,
                isGenerating = true,
                status = GenerationStatus.THINKING,
            ),
            taskPanel = taskPanel,
        )

        rule.onNodeWithTag(UiTags.CHAT_TASK_CLOSE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CAPSULE)
            .assertContentDescriptionEquals(resource(R.string.chat_task_generating))
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_PANEL_HEADER).performClick()
        rule.onNodeWithTag(UiTags.CHAT_TASK_COMPLETE).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CONTINUE).assertIsNotEnabled()
    }

    @Test
    fun planBarSharesComposerIslandAndCompletedPlanStaysHiddenOnOpen() {
        var plan by mutableStateOf(
            taskPanelUiState(listOf(TaskStep(id = "plan", title = "发布检查", status = "doing")), isGenerating = false),
        )
        render(uiState = ChatUiState(session = session), taskPanelProvider = { plan })

        // 计划区位于输入框浮岛内部，与输入区共用同一底色容器。
        val island = rule.onNodeWithTag(UiTags.CHAT_INPUT_BAR_SURFACE).fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag(UiTags.CHAT_TASK_CARD).fetchSemanticsNode().boundsInRoot
        assertThat(card.top).isAtLeast(island.top)
        assertThat(card.bottom).isAtMost(island.bottom)
        assertThat(card.width).isWithin(1f).of(island.width)

        // 打开会话时已完成的计划不出现。
        rule.runOnIdle {
            plan = taskPanelUiState(listOf(TaskStep(id = "other", title = "旧计划", status = "done")), isGenerating = false)
        }
        rule.onNodeWithTag(UiTags.CHAT_TASK_CARD).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_TASK_CAPSULE).assertDoesNotExist()
    }

    @Test
    fun approvalState_rendersToolDetailsAndDispatchesApprove() {
        var approved = false
        render(
            uiState = ChatUiState(
                session = session,
                approvalRequest = ApprovalRequest(
                    assistantMessageId = "assistant-approval",
                    identityHash = "preview-hash",
                    calls = listOf(
                        ApprovalCallIdentity(
                            toolCallId = "call-write",
                            runtimeToolId = "write_file",
                            toolName = "write_file",
                            argumentsDigest = "digest-write",
                            definitionDigest = "definition-write",
                            requiresApproval = true,
                            argumentsSummary = "{\"path\":\"notes.md\"}",
                            risk = "file_write",
                        ),
                        ApprovalCallIdentity(
                            toolCallId = "call-delete",
                            runtimeToolId = "delete_file",
                            toolName = "delete_file",
                            argumentsDigest = "digest-delete",
                            definitionDigest = "definition-delete",
                            requiresApproval = true,
                            argumentsSummary = "{\"path\":\"old.md\"}",
                            risk = "delete",
                        ),
                    ),
                    reason = "Update workspace",
                ),
            ),
            actions = ChatScreenActions(onApprove = { approved = true }),
        )

        rule.onNodeWithTag(UiTags.CHAT_STATE_APPROVAL).assertHeightIsAtLeast(100.dp)
        rule.onAllNodesWithText(resource(R.string.chat_step_file_edit_name), substring = true).assertCountEquals(2)
        rule.onNodeWithText("notes.md", substring = true).assertIsDisplayed()
        rule.onNodeWithText("old.md", substring = true).assertIsDisplayed()
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onNodeWithText("delete_file").assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.chat_approval_risk_file_write)).assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.chat_approval_risk_delete)).assertIsDisplayed()
        rule.onNodeWithText("Update workspace", substring = true).assertIsDisplayed()
        fun textColor(text: String): androidx.compose.ui.graphics.Color {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().layoutInput.style.color
        }
        assertThat(textColor("Update workspace")).isEqualTo(textColor("write_file"))
        assertThat(textColor("Update workspace")).isNotEqualTo(textColor(resource(R.string.chat_approval_risk_file_write)))
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).performClick()
        rule.runOnIdle { assertThat(approved).isTrue() }
    }

    @Test
    fun legacyApprovalKeepsArgumentsInOneParameterBlock() {
        render(ChatUiState(session = session, approvalRequest = ApprovalRequest(
            toolName = "write_file", args = "{\"path\":\"legacy.md\"}",
            reason = "Execution mode: semi\nUpdate workspace",
        )))
        rule.onNodeWithText("write_file").assertIsDisplayed()
        rule.onAllNodesWithText("{\"path\":\"legacy.md\"}").assertCountEquals(1)
        rule.onNodeWithText("Update workspace").assertIsDisplayed()
        rule.onNodeWithText("Execution mode: semi").assertDoesNotExist()
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
    fun assistantMetadata_shortModelName_layout_keepsRightAlignmentAndVisibleTime() {
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

        val modelMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA, useUnmergedTree = true)
        val timeMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA, useUnmergedTree = true)
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
        assertThat(timeBounds.right).isGreaterThan(centerX)
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

        val modelMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA, useUnmergedTree = true)
        val timeMetadata = rule.onNodeWithTag(UiTags.CHAT_ASSISTANT_TIME_METADATA, useUnmergedTree = true)
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
        assertThat(timeBounds.right).isGreaterThan(centerX)
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
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.CHAT_ATTACHMENT_MENU).assertDoesNotExist()
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
        taskPanel: TaskPanelUiState? = null,
        taskPanelProvider: (() -> TaskPanelUiState?)? = null,
    ) {
        rule.setContent {
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = uiState,
                        modelDisplayNames = modelDisplayNames,
                        draftDocuments = draftDocuments,
                        taskPanel = taskPanelProvider?.invoke() ?: taskPanel,
                    ),
                    actions = actions,
                )
            }
        }
    }

    private fun resource(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun assertMinimumTouchTarget(tag: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        val density = node.layoutInfo.density.density
        assertThat(bounds.width / density).isAtLeast(47.5f)
        assertThat(bounds.height / density).isAtLeast(47.5f)
    }
}
