package com.promenar.nexara.ui.hub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class AgentSessionsMaterialTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun session(
        id: String,
        title: String,
        lastMessage: String = "Preview message",
    ) = Session(
        id = id,
        agentId = "agent-preview",
        title = title,
        lastMessage = lastMessage,
        updatedAt = 1_720_000_000_000L,
    )

    @Test
    fun searchClearAndTopBarBackRestoreTheListBeforePageBack() {
        var query by mutableStateOf("")
        var searchActive by mutableStateOf(false)
        val backCount = AtomicInteger(0)
        val sessions = listOf(session("first", "First session"))
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = if (query.isBlank()) sessions else emptyList(),
                        agentName = "Preview agent",
                        searchQuery = query,
                        searchActive = searchActive,
                    ),
                    actions = AgentSessionsScreenActions(
                        onSearch = { query = it },
                        onSearchActiveChange = { searchActive = it },
                        onNavigateBack = { backCount.incrementAndGet() },
                    ),
                )
            }
        }

        val searchDescription = resources.getString(R.string.common_search_placeholder)
        rule.onNodeWithContentDescription(searchDescription).performClick()
        rule.onNodeWithContentDescription(searchDescription)
            .performTextInput("missing")
        rule.onNodeWithContentDescription(searchDescription).performImeAction()
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithText(resources.getString(R.string.common_search_no_results)).assertExists()

        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_clear)).performClick()
        assertThat(query).isEmpty()
        rule.onNodeWithContentDescription(searchDescription).performTextInput("missing-again")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()

        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_back)).performClick()
        rule.waitForIdle()

        assertThat(searchActive).isFalse()
        assertThat(query).isEmpty()
        assertThat(backCount.get()).isEqualTo(0)
        rule.onNodeWithText("First session").assertExists()

        Espresso.pressBack()
        rule.waitForIdle()

        assertThat(backCount.get()).isEqualTo(1)
    }

    @Test
    fun scrollingPositionSurvivesEnteringAndLeavingSearch() {
        var searchActive by mutableStateOf(false)
        var query by mutableStateOf("")
        val sessions = (0 until 30).map { index ->
            session("session-$index", "Session $index")
        }
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = if (query.isBlank()) sessions else listOf(sessions.first()),
                        agentName = "Preview agent",
                        searchQuery = query,
                        searchActive = searchActive,
                    ),
                    actions = AgentSessionsScreenActions(
                        onSearch = { query = it },
                        onSearchActiveChange = { searchActive = it },
                    ),
                )
            }
        }

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Session 24"))
        rule.onNodeWithText("Session 24").assertExists()
        val search = resources.getString(R.string.common_search_placeholder)
        rule.onNodeWithContentDescription(search).performClick()
        rule.onNodeWithContentDescription(search).performTextInput("missing")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithText("Session 0").assertExists()
        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_back)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Session 24").assertExists()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun accessibilityActionsPinAndRequestConfirmedDeleteWithoutOpening() {
        val opened = AtomicReference<String?>(null)
        val pinned = AtomicReference<String?>(null)
        val deleted = AtomicReference<String?>(null)
        var pendingDelete by mutableStateOf<String?>(null)
        val title = "Accessible session actions"
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = listOf(session("accessible", title)),
                        agentName = "Preview agent",
                        pendingDeleteSessionId = pendingDelete,
                    ),
                    actions = AgentSessionsScreenActions(
                        onOpenSession = opened::set,
                        onPinSession = pinned::set,
                        onRequestDelete = { pendingDelete = it },
                        onCancelDelete = { pendingDelete = null },
                        onConfirmDelete = {
                            deleted.set(it)
                            pendingDelete = null
                        },
                    ),
                )
            }
        }

        val row = rule.onNodeWithText(title)
        row.performCustomAccessibilityActionWithLabel(resources.getString(R.string.common_cd_pin))
        row.performCustomAccessibilityActionWithLabel(resources.getString(R.string.common_cd_delete))
        rule.onNodeWithText(resources.getString(R.string.session_settings_delete_title)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_delete)).performClick()

        assertThat(pinned.get()).isEqualTo("accessible")
        assertThat(deleted.get()).isEqualTo("accessible")
        assertThat(opened.get()).isNull()
    }

    @Test
    fun rowClickNavigatesToTheSelectedSession() {
        val opened = AtomicReference<String?>(null)
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = listOf(session("open", "Open session")),
                        agentName = "Preview agent",
                    ),
                    actions = AgentSessionsScreenActions(
                        onOpenSession = opened::set,
                    ),
                )
            }
        }

        rule.onNodeWithText("Open session").performClick()

        assertThat(opened.get()).isEqualTo("open")
    }

    @Test
    fun shortHorizontalSwipeDoesNotOpenTheSession() {
        val opened = AtomicReference<String?>(null)
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = listOf(session("swipe", "Swipe session")),
                        agentName = "Preview agent",
                    ),
                    actions = AgentSessionsScreenActions(
                        onOpenSession = opened::set,
                    ),
                )
            }
        }

        rule.onNodeWithText("Swipe session")
            .performTouchInput {
                swipe(
                    start = center,
                    end = center - Offset(120f, 0f),
                    durationMillis = 200,
                )
            }
        rule.waitForIdle()

        assertThat(opened.get()).isNull()
    }

    @Test
    fun longRightSwipePinsWithoutOpeningOrDeleting() {
        val opened = AtomicReference<String?>(null)
        val pinned = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        val title = "Long swipe session title that spans enough width for the gesture"
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = listOf(session("pin", title)),
                        agentName = "Preview agent",
                    ),
                    actions = AgentSessionsScreenActions(
                        onOpenSession = opened::set,
                        onPinSession = { pinned.set(true) },
                        onRequestDelete = { deleted.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithText(title).performTouchInput {
            swipe(start = centerLeft, end = centerRight, durationMillis = 500)
        }
        rule.waitUntil(timeoutMillis = 3_000) { pinned.get() }

        assertThat(opened.get()).isNull()
        assertThat(deleted.get()).isFalse()
    }

    @Test
    fun longLeftSwipeRequestsDeleteWithoutOpeningOrPinning() {
        val opened = AtomicReference<String?>(null)
        val pinned = AtomicBoolean(false)
        val deleted = AtomicBoolean(false)
        val title = "Long delete swipe session title that spans enough width for the gesture"
        rule.setContent {
            NexaraTheme {
                AgentSessionsScreenContent(
                    state = AgentSessionsScreenState(
                        sessions = listOf(session("delete", title)),
                        agentName = "Preview agent",
                    ),
                    actions = AgentSessionsScreenActions(
                        onOpenSession = opened::set,
                        onPinSession = { pinned.set(true) },
                        onRequestDelete = { deleted.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithText(title).performTouchInput {
            swipe(start = centerRight, end = centerLeft, durationMillis = 500)
        }
        rule.waitUntil(timeoutMillis = 3_000) { deleted.get() }

        assertThat(opened.get()).isNull()
        assertThat(pinned.get()).isFalse()
    }
}
