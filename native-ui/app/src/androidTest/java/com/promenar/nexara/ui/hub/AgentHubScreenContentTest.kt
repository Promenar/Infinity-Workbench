package com.promenar.nexara.ui.hub

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.testing.UiTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AgentHubScreenContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun hubFloatingAddDispatchesWithoutOpeningSession() {
        val add = AtomicBoolean(false)
        val open = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(),
                    actions = AgentHubScreenActions(
                        onRequestAdd = { add.set(true) },
                        onOpenChat = { open.set(true) },
                    ),
                )
            }
        }
        rule.onNodeWithTag(UiTags.HUB_ADD_AGENT).assertHasClickAction().performClick()
        assertThat(add.get()).isTrue()
        assertThat(open.get()).isFalse()
    }

    @Test
    fun expandingAndCollapsingAgentPreservesScrolledRow() {
        val agents = (0 until 24).map { index ->
            AgentDisplayItem(
                agent = previewAgent("agent-$index", "Agent $index", "Description $index"),
                title = "Agent $index",
                subtitle = "Description $index",
            )
        }
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(AgentHubScreenState(displayAgents = agents), AgentHubScreenActions())
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Agent 18"))
        rule.onNode(hasText("Agent 18") and hasClickAction()).performClick()
        rule.onNodeWithText(resources.getString(R.string.hub_sessions_empty)).assertIsDisplayed()
        rule.onNode(hasText("Agent 18") and hasClickAction()).performClick()
        rule.onNodeWithText("Agent 18").assertIsDisplayed()
    }

    private fun previewAgent(
        id: String,
        name: String,
        description: String,
        isPinned: Boolean = false,
        avatarPath: String? = null,
    ) = Agent(
        id = id,
        name = name,
        description = description,
        icon = "A",
        color = "#5B8DEF",
        isPinned = isPinned,
        avatarPath = avatarPath,
    )

    @Test
    fun customAvatarPathSelectsTheCustomImageBranchInHubRow() {
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-custom",
                                    name = "Custom Avatar",
                                    description = "Uses the persisted image",
                                    avatarPath = "/data/user/0/test/files/avatars/custom.img",
                                ),
                                title = "Custom Avatar",
                                subtitle = "Uses the persisted image",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(
            "hub_agent_custom_avatar:agent-custom",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun cardActionsEntryOpensLocalizedMenu() {
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-coder",
                                    name = "Coding Expert",
                                    description = "Full-stack development",
                                    isPinned = false,
                                ),
                                title = "Coding Expert",
                                subtitle = "Full-stack development",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentActions("agent-coder"))
            .assertHasClickAction()
            .performClick()

        rule.onNodeWithText(resources.getString(R.string.common_cd_pin)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_edit)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_delete)).assertExists()
    }

    @Test
    fun rowClickExpandsSessionsAndCreateDispatchesAgent() {
        val openSession = AtomicBoolean(false)
        val createSession = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-coder",
                                    name = "Coding Expert",
                                    description = "Full-stack development",
                                ),
                                title = "Coding Expert",
                                subtitle = "Full-stack development",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(
                        onOpenChat = { openSession.set(true) },
                        onCreateSession = { agentId -> createSession.set(agentId == "agent-coder") },
                    ),
                )
            }
        }

        rule.onNode(hasText("Coding Expert") and hasClickAction())
            .assertHasClickAction()
            .performClick()

        rule.onNodeWithText(resources.getString(R.string.hub_sessions_empty)).assertIsDisplayed()
        assertThat(openSession.get()).isFalse()
        rule.onNodeWithText(resources.getString(R.string.sessions_btn_new)).performClick()
        assertThat(createSession.get()).isTrue()
    }

    @Test
    fun expandedSessionClickDispatchesExactSessionId() {
        val opened = java.util.concurrent.atomic.AtomicReference<String>()
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(AgentDisplayItem(
                            agent = previewAgent("agent-coder", "Coding Expert", "Development"),
                            title = "Coding Expert",
                            subtitle = "Development",
                        )),
                        sessionsByAgent = mapOf("agent-coder" to listOf(
                            Session(id = "session-exact", agentId = "agent-coder", title = "Existing conversation"),
                        )),
                    ),
                    actions = AgentHubScreenActions(onOpenChat = { opened.set(it) }),
                )
            }
        }
        rule.onNode(hasText("Coding Expert") and hasClickAction()).performClick()
        assertThat(opened.get()).isNull()
        rule.onNodeWithText("Existing conversation").performClick()
        assertThat(opened.get()).isEqualTo("session-exact")
    }

    @Test
    fun menuClickDoesNotNavigateToSession() {
        val openSession = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-coder",
                                    name = "Coding Expert",
                                    description = "Full-stack development",
                                ),
                                title = "Coding Expert",
                                subtitle = "Full-stack development",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(
                        onOpenChat = { openSession.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentActions("agent-coder"))
            .assertHasClickAction()
            .performClick()

        rule.onNodeWithText(resources.getString(R.string.common_cd_pin)).assertExists()
        assertThat(openSession.get()).isFalse()
    }

    @Test
    fun pinnedRowExposesLocalizedStateDescription() {
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-pinned",
                                    name = "Pinned Agent",
                                    description = "Pinned for quick access",
                                    isPinned = true,
                                ),
                                title = "Pinned Agent",
                                subtitle = "Pinned for quick access",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-pinned"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.sessions_tag_pinned),
                ),
            )
    }

    @Test
    fun shortSwipeDoesNotTriggerDeleteOrNavigationOnHighDensityDevice() {
        val deleteRequested = AtomicBoolean(false)
        val openSession = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-swipe",
                                    name = "Swipe Agent",
                                    description = "Swipe threshold regression",
                                ),
                                title = "Swipe Agent",
                                subtitle = "Swipe threshold regression",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(
                        onRequestDelete = { deleteRequested.set(true) },
                        onOpenChat = { openSession.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-swipe"))
            .performTouchInput {
                swipe(
                    start = center,
                    end = center - Offset(120f, 0f),
                    durationMillis = 200,
                )
            }
        rule.waitForIdle()

        assertThat(deleteRequested.get()).isFalse()
        assertThat(openSession.get()).isFalse()
    }

    @Test
    fun emptyStateAddButtonIsClickable() {
        val clicked = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = emptyList()),
                    actions = AgentHubScreenActions(
                        onRequestAdd = { clicked.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.HUB_EMPTY_STATE).assertExists()
        rule.onNodeWithTag(UiTags.HUB_EMPTY_ADD_AGENT)
            .assertHasClickAction()
            .performClick()

        assertThat(clicked.get()).isTrue()
    }
}
