package com.promenar.nexara

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_ASKED
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_PREFS
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.stableModelId
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityChatFlowE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: MainActivityE2eApplication = ApplicationProvider.getApplicationContext()
    private val sessionId = "main-activity-e2e-session"
    private val firstModelId = stableModelId("e2e-provider", "model-a")
    private val secondModelId = stableModelId("e2e-provider", "model-b")
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() = runBlocking {
        waitForStartupReady()
        app.resetE2eObservations()
        prepareModels()
        seedSession(approval = null)
        assertThat(app.chatStore.getSession(sessionId)?.messages).hasSize(1)
        app.getSharedPreferences("nexara_onboarding", 0)
            .edit().putString("step", "LANGUAGE").commit()
        assertThat(
            app.getSharedPreferences(
                GENERATION_NOTIFICATION_PERMISSION_PREFS,
                Context.MODE_PRIVATE,
            ).edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).commit(),
        ).isTrue()

        val intent = Intent(app, MainActivity::class.java).apply {
            putExtra("com.promenar.nexara.extra.CHAT_SESSION_ID_FOR_TESTING", sessionId)
        }
        scenario = ActivityScenario.launch(intent)
        scenario.onActivity { activity ->
            assertThat(activity.application).isSameInstanceAs(app)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(UiTags.CHAT_ROOT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == sessionId
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(UiTags.CHAT_STATE_READY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun tearDown() = runBlocking {
        if (::scenario.isInitialized) scenario.close()
        app.sessionRepository.delete(sessionId)
        app.chatStore.clear()
        ProviderManager.getInstance().let { manager ->
            manager.deleteModel(firstModelId)
            manager.deleteModel(secondModelId)
        }
    }

    @Test
    fun mainActivity模型切换持久化并进入生成路由且审批同一ViewModel回传() {
        compose.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(UiTags.chatModelOption(secondModelId)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.chatModelOption(secondModelId)).performScrollTo()
        compose.waitForIdle()
        val modelOption = compose.onNodeWithTag(UiTags.chatModelOption(secondModelId))
        modelOption.assertIsDisplayed()
        val optionBounds = modelOption.fetchSemanticsNode().boundsInRoot
        android.util.Log.i("NexaraModelE2E", "option_bounds=$optionBounds")
        assertThat(optionBounds.width).isAtLeast(48f)
        assertThat(optionBounds.height).isAtLeast(48f)
        modelOption.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.waitUntil(10_000) {
            app.chatStore.getSession(sessionId)?.modelId == secondModelId
        }
        assertThat(runBlocking { app.sessionRepository.getById(sessionId) }?.modelId)
            .isEqualTo(secondModelId)

        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("route selected model")
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION).performClick()
        compose.waitUntil(10_000) { app.recordingCoordinator.requests.size == 1 }
        compose.onNodeWithTag(UiTags.NOTIFICATION_PERMISSION_DIALOG).assertDoesNotExist()
        assertThat(app.recordingCoordinator.requests.single().runtimePolicy)
            .isEqualTo(expectedRuntimePolicyForDevice())
        assertThat(app.recordingCoordinator.selectedModelsAtStart.single())
            .isEqualTo(secondModelId)

        runBlocking { seedSession(approval = continuationApproval()) }
        compose.onNodeWithTag(UiTags.CHAT_APPROVAL_CARD).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).performClick()
        compose.waitUntil(10_000) { app.recordingCoordinator.requests.size == 2 }
        compose.waitUntil(10_000) {
            compose.onAllNodes(UiTags.CHAT_APPROVAL_CARD).fetchSemanticsNodes().isEmpty()
        }
        assertThat(runBlocking { app.sessionRepository.getById(sessionId) }?.approvalRequest).isNull()

        runBlocking { seedSession(approval = continuationApproval()) }
        compose.onNodeWithTag(UiTags.CHAT_APPROVAL_CARD).assertIsDisplayed()
        val startsBeforeReject = app.recordingCoordinator.requests.size
        compose.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE).performClick()
        compose.waitUntil(10_000) {
            app.chatStore.getSession(sessionId)?.approvalRequest == null
        }
        assertThat(app.recordingCoordinator.requests).hasSize(startsBeforeReject)
        val rejected = runBlocking { app.sessionRepository.getById(sessionId) }
        assertThat(rejected?.approvalRequest).isNull()
        assertThat(rejected?.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(app.viewModelCreateCount).isEqualTo(1)
    }

    private suspend fun waitForStartupReady() {
        repeat(200) {
            if (app.startupState.value == com.promenar.nexara.data.backup.BackupStartupState.Ready) {
                return
            }
            delay(50)
        }
        error("NexaraApplication startup did not become Ready: ${app.startupState.value}")
    }

    private fun prepareModels() {
        val manager = ProviderManager.getInstance()
        manager.providerModels.value
            .filter { it.id == firstModelId || it.id == secondModelId }
            .forEach { manager.deleteModel(it.id) }
        manager.addModel(e2eModel("model-a", "E2E Model A"))
        manager.addModel(e2eModel("model-b", "E2E Model B"))
    }

    private fun e2eModel(remoteId: String, name: String) = ModelInfo(
        name = name,
        id = stableModelId("e2e-provider", remoteId),
        remoteModelId = remoteId,
        description = "MainActivity E2E fixture",
        enabled = true,
        type = "chat",
        capabilities = listOf("chat"),
        providerName = "E2E Provider",
        providerId = "e2e-provider",
    )

    private suspend fun seedSession(approval: ApprovalRequest?) {
        app.sessionRepository.delete(sessionId)
        val assistant = Message(
            id = "e2e-assistant",
            role = MessageRole.ASSISTANT,
            content = "fixture response",
            modelId = secondModelId,
        )
        val session = Session(
            id = sessionId,
            agentId = "e2e-agent",
            title = "MainActivity E2E",
            modelId = if (approval == null) firstModelId else secondModelId,
            approvalRequest = approval,
            loopStatus = if (approval == null) LoopStatus.IDLE else LoopStatus.WAITING_FOR_APPROVAL,
            messages = listOf(assistant),
        )
        app.sessionRepository.create(session.copy(messages = emptyList()))
        app.messageRepository.insert(assistant, sessionId)
        app.chatStore.update { ChatState(sessions = listOf(session)) }
    }

    private fun continuationApproval() = ApprovalRequest(
        type = "continuation",
        reason = "MainActivity E2E approval callback",
    )

    private fun expectedRuntimePolicyForDevice(): GenerationRuntimePolicy =
        if (Build.VERSION.SDK_INT >= 33) {
            GenerationRuntimePolicy.FOREGROUND_ONLY
        } else {
            GenerationRuntimePolicy.BACKGROUND_ALLOWED
        }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodes(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
}
