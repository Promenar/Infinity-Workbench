package com.promenar.nexara.ui.hub

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.testing.UiTags
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

        com.google.common.truth.Truth.assertThat(clicked.get()).isTrue()
    }
}
