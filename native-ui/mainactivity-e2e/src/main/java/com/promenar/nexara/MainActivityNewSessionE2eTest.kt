package com.promenar.nexara

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.LoopStatus
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNewSessionE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: MainActivityE2eApplication = ApplicationProvider.getApplicationContext()
    private val agentId = "new-session-e2e-agent"
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var createdSessionId: String? = null

    @Before
    fun setUp(): Unit = runBlocking {
        waitForStartupReady()
        app.resetE2eObservations()
        app.sessionRepository.getAll()
            .filter { it.agentId == agentId }
            .forEach { app.sessionRepository.delete(it.id) }
        app.agentRepository.delete(agentId)
        app.agentRepository.create(
            Agent(
                id = agentId,
                name = "New Session E2E Agent",
                description = "Exercises the real session-list create route",
            ),
        )
        app.getSharedPreferences("nexara_onboarding", Context.MODE_PRIVATE)
            .edit().putString("step", OnboardingStep.COMPLETED.name).commit()

        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        waitForNode(UiTags.hubAgentCard(agentId))
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (::scenario.isInitialized) scenario.close()
        createdSessionId?.let { app.sessionRepository.delete(it) }
        app.agentRepository.delete(agentId)
        app.chatStore.clear()
        Unit
    }

    @Test
    fun 新建会话从列表进入聊天且状态可恢复(): Unit = runBlocking {
        compose.onNodeWithTag(UiTags.hubAgentCard(agentId)).assertIsDisplayed().performClick()

        val createLabel = app.getString(R.string.sessions_cd_new)
        compose.waitUntil(15_000) {
            runCatching {
                compose.onAllNodesWithContentDescription(createLabel)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription(createLabel).assertIsDisplayed().performClick()

        compose.waitUntil(15_000) {
            app.createdChatViewModel?.uiState?.value?.session
                ?.takeIf { it.agentId == agentId }
                ?.id
                ?.also { createdSessionId = it } != null
        }
        waitForNode(UiTags.CHAT_STATE_EMPTY)

        val sessionId = requireNotNull(createdSessionId)
        val persisted = app.sessionRepository.getById(sessionId)
        val inMemory = app.chatStore.getSession(sessionId)
        assertThat(persisted?.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(inMemory?.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(app.createdChatViewModel?.uiState?.value?.session?.id).isEqualTo(sessionId)
        compose.onNodeWithTag(UiTags.CHAT_STATE_EMPTY).assertIsDisplayed()
        Unit
    }

    private fun waitForNode(tag: String) {
        compose.waitUntil(15_000) {
            runCatching {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
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
}
