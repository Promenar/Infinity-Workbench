package com.promenar.nexara.ui.hub

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Agent
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

    private fun previewAgent(
        id: String,
        name: String,
        description: String,
        isPinned: Boolean = false,
    ) = Agent(
        id = id,
        name = name,
        description = description,
        icon = "A",
        color = "#5B8DEF",
        isPinned = isPinned,
    )

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
    fun rowClickNavigatesToSession() {
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
                        onOpenSession = { openSession.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-coder"))
            .assertHasClickAction()
            .performClick()

        assertThat(openSession.get()).isTrue()
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
                        onOpenSession = { openSession.set(true) },
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
                        onOpenSession = { openSession.set(true) },
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
