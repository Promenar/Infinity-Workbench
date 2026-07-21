package com.promenar.nexara.onboarding

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.MainActivity
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass

/**
 * 真实 Activity/Compose 设备端首次引导回归。
 *
 * 进程级恢复由主控分两次 instrumentation 调用执行：首次运行推进并保留偏好，随后
 * `adb shell am force-stop` 再以保留数据方式运行验证入口；本类自身不把 JVM 重建冒充进程死亡。
 */
class OnboardingAndroidEndToEndTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private var originalProviderSummary: com.promenar.nexara.data.model.ProviderSummary? = null
    private var originalMainSlot: com.promenar.nexara.data.local.inference.SlotState? = null
    private var originalChatState: com.promenar.nexara.ui.chat.ChatState? = null

    @Before
    fun resetBeforeEachDeviceFlow() {
        if (phaseArgument() == null) resetPersistentState()
        val app = waitForStartupReady()
        originalProviderSummary = ProviderManager.getInstance().getProviderSummary("default")
        originalMainSlot = if (app.localInferenceRuntimeGate.isAvailable) {
            app.localInferenceEngine.mainSlot.value
        } else {
            null
        }
        originalChatState = app.chatStore.current
    }

    @After
    fun testHooksDoNotMutateProviderOrLocalSlot() {
        rule.activity.intent.removeExtra(MainActivity.EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING)
        rule.activity.intent.removeExtra(MainActivity.EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING)
        val app = rule.activity.application as NexaraApplication
        originalChatState?.let { snapshot -> app.chatStore.update { snapshot } }
        if (app.startupState.value == BackupStartupState.Ready) {
            assertEquals(originalProviderSummary, ProviderManager.getInstance().getProviderSummary("default"))
            if (app.localInferenceRuntimeGate.isAvailable) {
                assertEquals(
                    originalMainSlot,
                    app.localInferenceEngine.mainSlot.value,
                )
            }
        }
    }

    private fun resetPersistentState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
            .edit().putString("language", "zh").commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        rule.activityRule.scenario.recreate()
        waitForStep(OnboardingStep.LANGUAGE)
    }

    @Test
    fun freshInstall_localeAndEveryCheckpointSurviveRecreate_thenOnlyMatchingSuccessCompletes() {
        assumeTrue("常规E2E不在force-stop分阶段模式运行", phaseArgument() == null)
        rule.onNodeWithTag("onboarding_step_language").assertIsDisplayed()
        rule.activity.intent.putExtra(MainActivity.EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING, true)
        rule.onNodeWithText("English").performClick()
        waitForStep(OnboardingStep.PROVIDER)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Connect an AI provider").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Connect an AI provider").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_step_provider").assertIsDisplayed()

        rule.runOnIdle {
            check(rule.activity.onboardingStateStoreForTesting().recordProviderSaved("provider-test"))
        }
        waitForStep(OnboardingStep.CONNECTION)
        recreateAndAssert(OnboardingStep.CONNECTION, "onboarding_step_connection")

        rule.runOnIdle {
            check(
                rule.activity.onboardingStateStoreForTesting()
                    .recordConnectionResult("provider-test", succeeded = true)
            )
        }
        waitForStep(OnboardingStep.MODEL)
        recreateAndAssert(OnboardingStep.MODEL, "onboarding_step_model")

        // 测试入口强制提供真实空列表；UI 必须阻断并提供可恢复动作。
        rule.onAllNodesWithTag("onboarding_model_option").assertCountEquals(0)
        rule.onNodeWithTag("onboarding_model_empty").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_model_refresh").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_model_back_connection").assertIsDisplayed()
        assertStep(OnboardingStep.MODEL)
        recreateAndAssert(OnboardingStep.MODEL, "onboarding_step_model")
        rule.runOnIdle {
            check(
                rule.activity.onboardingStateStoreForTesting()
                    .selectModel("provider-test::model-test")
            )
        }
        waitForStep(OnboardingStep.AGENT)
        recreateAndAssert(OnboardingStep.AGENT, "onboarding_step_agent")

        rule.runOnIdle {
            check(
                rule.activity.onboardingStateStoreForTesting()
                    .recordAgentCreated("agent-test", "session-test")
            )
        }
        waitForStep(OnboardingStep.FIRST_CHAT)
        recreateAndAssert(OnboardingStep.FIRST_CHAT, "onboarding_step_first_chat")

        val app = rule.activity.application as NexaraApplication
        runBlocking {
            val sessionDao = app.database.sessionDao()
            val now = System.currentTimeMillis()
            val existing = sessionDao.getById("session-test")
            val fixture = existing?.copy(
                agentId = "agent-test",
                modelId = "provider-test::model-test",
                updatedAt = now,
            ) ?: SessionEntity(
                id = "session-test",
                agentId = "agent-test",
                title = "Onboarding E2E",
                modelId = "provider-test::model-test",
                createdAt = now,
                updatedAt = now,
            )
            if (existing == null) sessionDao.insert(fixture) else sessionDao.update(fixture)
        }
        rule.runOnIdle {
            app.chatStore.update { state ->
                state.copy(
                    sessions = listOf(
                        Session(
                            id = "session-test",
                            agentId = "agent-test",
                            messages = listOf(
                                Message(id = "assistant-partial", role = MessageRole.ASSISTANT, content = "partial")
                            ),
                        ),
                        Session(
                            id = "other-session",
                            agentId = "agent-test",
                            messages = listOf(
                                Message(
                                    id = "other-success",
                                    role = MessageRole.ASSISTANT,
                                    content = "success in wrong session",
                                    status = "success",
                                )
                            ),
                        ),
                    )
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)
        rule.onNodeWithText(rule.activity.getString(R.string.onboarding_first_chat_action)).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ROOT).assertIsDisplayed()

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") it.copy(status = "error", isError = true) else it
                    }
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") {
                            it.copy(status = "cancelled", isError = false)
                        } else it
                    }
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") {
                            it.copy(status = "success", content = "successful reply", isError = false)
                        } else it
                    }
                )
            }
        }
        waitForStep(OnboardingStep.COMPLETED)
        rule.onNodeWithTag(UiTags.CHAT_ROOT).assertIsDisplayed()

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag(UiTags.HUB_ROOT).assertIsDisplayed()
        rule.onNodeWithTag("main_navigation_tab_library").performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_ROOT).assertIsDisplayed()

        rule.onNodeWithTag("main_navigation_tab_settings").performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertIsDisplayed()
        val ragConfigLabel = rule.activity.getString(R.string.settings_rag_config)
        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(hasText(ragConfigLabel))
        rule.onNodeWithText(ragConfigLabel)
            .performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.rag_config_title)).assertIsDisplayed()

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertIsDisplayed()
        val advancedRetrievalLabel = rule.activity.getString(R.string.settings_advanced_retrieval)
        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(hasText(advancedRetrievalLabel))
        rule.onNodeWithText(advancedRetrievalLabel)
            .performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.retrieval_title)).assertIsDisplayed()
    }

    @Test
    fun localProviderRespectsBuildCapabilityAndStaysOnConnection() {
        assumeTrue("常规E2E不在force-stop分阶段模式运行", phaseArgument() == null)
        val app = rule.activity.application as NexaraApplication
        rule.activity.intent.putExtra(MainActivity.EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING, true)
        rule.activityRule.scenario.recreate()
        waitForStep(OnboardingStep.LANGUAGE)
        rule.runOnIdle {
            val store = rule.activity.onboardingStateStoreForTesting()
            check(store.selectLanguage("zh"))
            check(store.recordProviderSaved("default"))
        }
        waitForStep(OnboardingStep.CONNECTION)
        rule.onNodeWithTag("onboarding_step_connection").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.onboarding_connection_action)).performClick()
        if (app.localInferenceRuntimeGate.isAvailable) {
            rule.onNodeWithText(rule.activity.getString(R.string.provider_form_btn_test)).performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.onboarding_local_model_unavailable))
                .assertIsDisplayed()
        } else {
            val unavailableMessage = rule.activity.getString(R.string.local_inference_release_unavailable)
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithText(unavailableMessage).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(unavailableMessage)
                .performScrollTo()
                .assertIsDisplayed()
            rule.onAllNodesWithText(rule.activity.getString(R.string.onboarding_local_model_unavailable))
                .assertCountEquals(0)
        }
        assertStep(OnboardingStep.CONNECTION)
    }

    @Test
    fun forceStopPhaseCheckpoint() {
        val phase = phaseArgument()
        assumeTrue("仅由主控通过onboardingPhase显式运行", phase != null)
        when (phase) {
            "seed_first_chat" -> {
                resetPersistentState()
                val store = rule.activity.onboardingStateStoreForTesting()
                rule.runOnIdle {
                    check(store.selectLanguage("zh"))
                    check(store.recordProviderSaved("provider-force-stop"))
                    check(store.recordConnectionResult("provider-force-stop", succeeded = true))
                    check(store.selectModel("provider-force-stop::model"))
                    check(store.recordAgentCreated("agent-force-stop", "session-force-stop"))
                }
                waitForStep(OnboardingStep.FIRST_CHAT)
            }
            "verify_first_chat" -> {
                waitForStep(OnboardingStep.FIRST_CHAT)
                rule.runOnIdle {
                    assertEquals(
                        "session-force-stop",
                        rule.activity.onboardingStateForTesting().sessionId,
                    )
                }
                rule.onNodeWithTag("onboarding_step_first_chat").assertIsDisplayed()
            }
            else -> error("未知onboardingPhase=$phase")
        }
    }

    private fun recreateAndAssert(step: OnboardingStep, tag: String) {
        rule.activityRule.scenario.recreate()
        waitForStep(step)
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForStep(step: OnboardingStep) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.activity.onboardingStateForTesting().step == step
        }
    }

    private fun assertStep(step: OnboardingStep) {
        rule.runOnIdle { assertEquals(step, rule.activity.onboardingStateForTesting().step) }
    }

    private fun waitForStartupReady(): NexaraApplication {
        val app = rule.activity.application as NexaraApplication
        rule.waitUntil(timeoutMillis = 20_000) {
            val state = app.startupState.value
            state == BackupStartupState.Ready || state == BackupStartupState.Blocked
        }
        check(app.startupState.value == BackupStartupState.Ready) {
            "NexaraApplication 启动恢复未就绪 (state=${app.startupState.value})，ProviderManager 暂不可读"
        }
        return app
    }

    private fun phaseArgument(): String? = InstrumentationRegistry.getArguments()
        .getString("onboardingPhase")

    companion object {
        @JvmStatic
        @BeforeClass
        fun resetBeforeInitialActivityLaunch() {
            if (InstrumentationRegistry.getArguments().getString("onboardingPhase") != null) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("nexara_settings", Context.MODE_PRIVATE)
                .edit().putString("language", "zh").commit()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
    }
}
