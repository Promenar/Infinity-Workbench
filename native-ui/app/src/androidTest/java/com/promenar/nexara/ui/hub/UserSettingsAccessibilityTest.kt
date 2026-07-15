package com.promenar.nexara.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.NexaraSettingsItem
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
    fun sharedSettingsItemExposesButtonRole48DpTargetAndRealClick() {
        val clicked = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Settings,
                    title = "Shared settings item",
                    onClick = { clicked.set(true) },
                )
            }
        }

        rule.onNodeWithText("Shared settings item")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        com.google.common.truth.Truth.assertThat(clicked.get()).isTrue()
    }

    @Test
    fun sharedSettingsItemLongTitleReflowsInsteadOfClipping() {
        val longTitle = "Very long settings title that must wrap naturally on a narrow phone layout"
        rule.setContent {
            NexaraTheme {
                Box(modifier = androidx.compose.ui.Modifier.width(220.dp)) {
                    NexaraSettingsItem(
                        icon = Icons.Rounded.Settings,
                        title = longTitle,
                        onClick = {},
                    )
                }
            }
        }

        rule.onNodeWithText(longTitle)
            .assertHeightIsAtLeast(72.dp)
    }

    @Test
    fun sharedSearchClearActionIs48DpAndDispatchesValueChange() {
        var query by mutableStateOf("DeepSeek")
        rule.setContent {
            NexaraTheme {
                NexaraSearchBar(
                    value = query,
                    onValueChange = { query = it },
                )
            }
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_clear))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        rule.onNodeWithContentDescription(resources.getString(R.string.common_search_placeholder))
            .assert(hasSetTextAction())

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(query).isEmpty()
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
    fun appLanguageEntryDispatchesRealDialogAction() {
        val opened = AtomicBoolean(false)

        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(selectedTab = SettingsTab.APP),
                    actions = UserSettingsHomeScreenActions(
                        onShowLanguageDialog = { opened.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_language))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(opened.get()).isTrue()
    }

    @Test
    fun languageOptionExposesRadioButtonSelectionAnd48DpTarget() {
        val selected = AtomicBoolean(false)

        rule.setContent {
            NexaraTheme {
                LanguageOption(
                    label = "中文",
                    isSelected = true,
                    onSelect = { selected.set(true) },
                )
            }
        }

        rule.onNodeWithText("中文")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(selected.get()).isTrue()
    }

    @Test
    fun appSettingsLongSubtitleReflowsAt2xAndAboutRemainsReachable() {
        val longModelName =
            "MiniMax-M3 multimodal reasoning and tool-calling production model with extended context"
        val aboutOpened = AtomicBoolean(false)

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                NexaraTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        UserSettingsHomeScreenContent(
                            state = UserSettingsHomeScreenState(
                                selectedTab = SettingsTab.APP,
                                summaryModelName = longModelName,
                                imageModelName = "FLUX.1 Schnell",
                                embeddingModelName = "BAAI/bge-m3",
                                rerankModelName = "Cohere Rerank v3",
                                versionName = "ACCESSIBILITY-ABOUT-DESTINATION",
                            ),
                            actions = UserSettingsHomeScreenActions(
                                onAboutClick = { aboutOpened.set(true) },
                            ),
                        )
                    }
                }
            }
        }

        rule.onNodeWithText(longModelName)
            .assertHeightIsAtLeast(96.dp)

        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(
                hasText("ACCESSIBILITY-ABOUT-DESTINATION", substring = true),
            )

        rule.onNodeWithText("ACCESSIBILITY-ABOUT-DESTINATION", substring = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(aboutOpened.get()).isTrue()
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
