package com.promenar.nexara.ui.hub

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.testing.UiTags
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class UserSettingsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun settingsTabsExposeRoleSelectionAnd48DpTouchTarget() {
        rule.setContent {
            NexaraTheme {
                TabBar(selectedTab = SettingsTab.APP, onTabSelected = {})
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_tab_app))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun githubFooterExposesButtonRoleLabelAnd48DpTarget() {
        val opened = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                GitHubProjectFooter { opened.set(true) }
            }
        }

        rule.onNodeWithContentDescription("github.com/promenar/nexara")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("GitHub footer has localized onClickLabel") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        resources.getString(R.string.rag_details_open_link)
                },
            )
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        com.google.common.truth.Truth.assertThat(opened.get()).isTrue()
    }

    @Test
    fun contentTabClickReallySwitchesSelectedTabViaCallback() {
        var selectedTab by mutableStateOf(SettingsTab.APP)

        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(
                        selectedTab = selectedTab,
                    ),
                    actions = UserSettingsHomeScreenActions(
                        onTabSelected = { selectedTab = it },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_TAB_PROVIDER)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performClick()

        rule.onNodeWithTag(UiTags.SETTINGS_TAB_PROVIDER)
            .assertIsSelected()
        com.google.common.truth.Truth.assertThat(selectedTab)
            .isEqualTo(SettingsTab.PROVIDER)
    }

    @Test
    fun providerAddButtonRealClickTriggersNavigationNotJustNodePresence() {
        val navigated = AtomicBoolean(false)

        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(selectedTab = SettingsTab.PROVIDER),
                    actions = UserSettingsHomeScreenActions(
                        onNavigateToSecondary = { route ->
                            navigated.set(route == "provider_form")
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_ADD_PROVIDER)
            .assertHasClickAction()
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(navigated.get()).isTrue()
    }

    @Test
    fun localInferenceEntryIsAbsentWhenUnavailableInReleaseSurface() {
        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(
                        selectedTab = SettingsTab.APP,
                        localInferenceAvailable = false,
                    ),
                    actions = UserSettingsHomeScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY).assertDoesNotExist()
    }

    @Test
    fun providerCardsExposeStableCardAndActionsTagsForEachProvider() {
        val providers = listOf(
            ProviderListItem(
                id = "provider-alpha",
                name = "Alpha Provider",
                typeName = ProtocolType.Generic_OpenAI_Compat.displayName,
                baseUrl = "https://api.alpha.example.com/v1",
            ),
            ProviderListItem(
                id = "provider-beta",
                name = "Beta Provider",
                typeName = ProtocolType.Anthropic_Messages.displayName,
                baseUrl = "https://api.beta.example.com/v1",
            ),
        )

        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(
                        selectedTab = SettingsTab.PROVIDER,
                        providers = providers,
                    ),
                    actions = UserSettingsHomeScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-alpha")).assertHasClickAction()
        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-beta")).assertHasClickAction()
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-alpha"),
            useUnmergedTree = true,
        ).assertExists()
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-beta"),
            useUnmergedTree = true,
        ).assertExists()
    }
}
