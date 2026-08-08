package com.promenar.nexara.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.NexaraSearchTopBar
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.settings.ProviderListScreenActions
import com.promenar.nexara.ui.settings.ProviderListScreenContent
import com.promenar.nexara.ui.settings.ProviderListScreenState
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.testing.UiTags
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UserSettingsAccessibilityTest {
    private class TestMotionDurationScale : MotionDurationScale {
        var value = 1f
        override val scaleFactor: Float
            get() = value
    }

    private val motionDurationScale = TestMotionDurationScale()

    @get:Rule
    val rule = createComposeRule(effectContext = motionDurationScale)

    @Before
    fun resetMotionDurationScale() {
        motionDurationScale.value = 1f
    }

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun settingsTabsShouldNotExistOnNewHomeScreen() {
        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
                    actions = UserSettingsHomeScreenActions()
                )
            }
        }
        rule.onNodeWithTag(UiTags.SETTINGS_TAB_APP).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.SETTINGS_TAB_PROVIDER).assertDoesNotExist()
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
    fun sharedSearchTopBarSupportsFocusImeSystemBackAndReducedMotionAt2xScale() {
        val longTitle = "A deliberately long management page title"
        val actionDescription = "Additional settings action"
        var query by mutableStateOf("")
        var searchActive by mutableStateOf(false)
        val backPressed = AtomicBoolean(false)
        val actionClicked = AtomicBoolean(false)
        motionDurationScale.value = 0f
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                NexaraTheme {
                    NexaraSearchTopBar(
                        title = longTitle,
                        query = query,
                        searchActive = searchActive,
                        onQueryChange = { query = it },
                        onSearchActiveChange = { searchActive = it },
                        onBack = { backPressed.set(true) },
                        actions = {
                            IconButton(
                                onClick = { actionClicked.set(true) },
                                modifier = androidx.compose.ui.Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = actionDescription,
                                )
                            }
                        },
                    )
                }
            }
        }

        val clearDescription = resources.getString(R.string.common_cd_clear)
        val placeholderDescription = resources.getString(R.string.common_search_placeholder)

        rule.onNodeWithText(longTitle)
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(actionDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        com.google.common.truth.Truth.assertThat(actionClicked.get()).isTrue()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription(placeholderDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(longTitle).assertDoesNotExist()
        rule.onNodeWithContentDescription(actionDescription).assertDoesNotExist()
        rule.mainClock.autoAdvance = true

        val searchField = rule.onNodeWithContentDescription(placeholderDescription)
        searchField
            .assert(hasSetTextAction())
            .assertIsFocused()
            .assertHeightIsAtLeast(48.dp)
            .performTextInput("DeepSeek")
        searchField.performImeAction()
        searchField.assertIsNotFocused()

        rule.onNodeWithContentDescription(clearDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(query).isEmpty()

        Espresso.pressBack()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(searchActive).isFalse()
        com.google.common.truth.Truth.assertThat(backPressed.get()).isFalse()

        Espresso.pressBack()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(backPressed.get()).isTrue()
    }

    @Test
    fun settingsSectionDividerSpansPastTheFixedIconColumnAt2xScale() {
        val rowTitle = "Aligned settings row"
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        NexaraSettingsSection(title = "Accessible section") {
                            NexaraSettingsItem(
                                icon = Icons.Rounded.Settings,
                                title = rowTitle,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }

        rule.onNodeWithText("Accessible section").assertIsDisplayed()
        rule.onNodeWithText(rowTitle)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)

        val rowTextLeft = rule.onNodeWithText(rowTitle, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val dividerLeft = rule.onNodeWithTag("nexara_settings_section_divider")
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        com.google.common.truth.Truth.assertThat(dividerLeft)
            .isLessThan(rowTextLeft)
    }

    @Test
    fun defaultModelsItemClickTriggersSecondaryNavigation() {
        var navigatedRoute: String? = null
        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
                    actions = UserSettingsHomeScreenActions(
                        onNavigateToSecondary = { navigatedRoute = it }
                    )
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_default_models))
            .performClick()

        com.google.common.truth.Truth.assertThat(navigatedRoute).isEqualTo("default_models")
    }

    @Test
    fun appLanguageEntryDispatchesRealDialogAction() {
        val opened = AtomicBoolean(false)

        rule.setContent {
            NexaraTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
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
    fun appSettingsSingleLineRowsRemainReachableAt2x() {
        val aboutTitle = resources.getString(R.string.settings_about_nexara)
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
                            state = UserSettingsHomeScreenState(),
                            actions = UserSettingsHomeScreenActions(
                                onAboutClick = { aboutOpened.set(true) },
                            ),
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(
                hasText(aboutTitle),
            )

        rule.onNodeWithText(aboutTitle)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
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
                ProviderListScreenContent(
                    state = ProviderListScreenState(),
                    actions = ProviderListScreenActions(onAddProvider = { navigated.set(true) }),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_ADD_PROVIDER)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
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
                        localInferenceAvailable = false,
                    ),
                    actions = UserSettingsHomeScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY).assertDoesNotExist()
    }

    @Test
    fun providerRowsExposeDistinctManageAndOverflowTargetsForEachProvider() {
        val apiConfiguredSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_enabled),
            resources.getString(R.string.settings_provider_api_key_configured),
        )
        val apiMissingSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_disabled),
            resources.getString(R.string.settings_provider_api_key_not_configured),
        )
        val providers = listOf(
            ProviderListItem(
                id = "provider-alpha",
                name = "Alpha Provider",
                typeName = ProtocolType.Generic_OpenAI_Compat.displayName,
                baseUrl = "https://api.alpha.example.com/v1",
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                hasApiKey = true,
                enabled = true,
            ),
            ProviderListItem(
                id = "provider-beta",
                name = "Beta Provider",
                typeName = ProtocolType.Anthropic_Messages.displayName,
                baseUrl = "https://api.beta.example.com/v1",
                protocolType = ProtocolType.Anthropic_Messages,
                hasApiKey = false,
                enabled = false,
            ),
        )

        rule.setContent {
            NexaraTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(providers = providers),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-alpha"))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    apiConfiguredSummary,
                ),
            )
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-beta"))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    apiMissingSummary,
                ),
            )
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-alpha"),
            useUnmergedTree = true,
        )
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-beta"),
            useUnmergedTree = true,
        )
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText(apiConfiguredSummary, useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithText(apiMissingSummary, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun providerRowsShowVertexCredentialStateAt2xWithoutLosingTalkBackOrOverflow() {
        val enabled = resources.getString(R.string.settings_provider_state_enabled)
        val disabled = resources.getString(R.string.settings_provider_state_disabled)
        val vertexConfigured =
            resources.getString(R.string.settings_provider_vertex_credentials_configured)
        val vertexMissing =
            resources.getString(R.string.settings_provider_vertex_credentials_not_configured)
        val configuredSummary = resources.getString(
            R.string.settings_provider_status_summary,
            enabled,
            vertexConfigured,
        )
        val missingSummary = resources.getString(
            R.string.settings_provider_status_summary,
            disabled,
            vertexMissing,
        )

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        ProviderListScreenContent(
                            state = ProviderListScreenState(
                                providers = listOf(
                                    ProviderListItem(
                                        id = "provider-vertex-configured",
                                        name = "Vertex Configured",
                                        typeName = ProtocolType.Google_VertexAI.displayName,
                                        baseUrl = "https://vertex.example.com/v1",
                                        protocolType = ProtocolType.Google_VertexAI,
                                        hasVertexCredentials = true,
                                        enabled = true,
                                    ),
                                    ProviderListItem(
                                        id = "provider-vertex-missing",
                                        name = "Vertex Missing",
                                        typeName = ProtocolType.Google_VertexAI.displayName,
                                        baseUrl = "https://vertex-missing.example.com/v1",
                                        protocolType = ProtocolType.Google_VertexAI,
                                        hasVertexCredentials = false,
                                        enabled = false,
                                    ),
                                ),
                            ),
                            actions = ProviderListScreenActions(),
                        )
                    }
                }
            }
        }

        listOf(
            "provider-vertex-configured" to configuredSummary,
            "provider-vertex-missing" to missingSummary,
        ).forEach { (providerId, summary) ->
            rule.onNodeWithTag(UiTags.SETTINGS_PROVIDER_LIST)
                .performScrollToNode(hasText(summary))
            rule.onNodeWithText(summary, useUnmergedTree = true)
                .assertIsDisplayed()
            rule.onNodeWithTag(UiTags.settingsProviderCard(providerId))
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        summary,
                    ),
                )
            rule.onNodeWithTag(
                UiTags.settingsProviderActions(providerId),
                useUnmergedTree = true,
            )
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun localProviderReportsNoCredentialsRequiredWithoutApiOrVertexMislabeling() {
        val expectedSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_enabled),
            resources.getString(R.string.settings_provider_credentials_not_required),
        )

        rule.setContent {
            NexaraTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = "provider-local",
                                name = "Local Inference",
                                typeName = ProtocolType.Local.displayName,
                                protocolType = ProtocolType.Local,
                                hasApiKey = true,
                                hasVertexCredentials = true,
                                enabled = true,
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        rule.onNodeWithText(expectedSummary, useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-local"))
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    expectedSummary,
                ),
            )
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-local"),
            useUnmergedTree = true,
        ).assertHasClickAction()
    }

    @Test
    fun providerManageAndOverflowEditDispatchDifferentRoutes() {
        val providerId = "provider-actions"
        val lastRoute = AtomicReference<String>()

        rule.setContent {
            NexaraTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = providerId,
                                name = "Actions Provider",
                                typeName = ProtocolType.Generic_OpenAI_Compat.displayName,
                                baseUrl = "https://api.example.com/v1",
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(
                        onProviderModels = { id -> lastRoute.set("provider_models/$id") },
                        onEditProvider = { id -> lastRoute.set("provider_form?providerId=$id") },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.settingsProviderCard(providerId))
            .performClick()
        com.google.common.truth.Truth.assertThat(lastRoute.get())
            .isEqualTo("provider_models/$providerId")

        lastRoute.set(null)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions(providerId),
            useUnmergedTree = true,
        ).performClick()
        com.google.common.truth.Truth.assertThat(lastRoute.get()).isNull()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:edit")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(lastRoute.get())
            .isEqualTo("provider_form?providerId=$providerId")
    }

    @Test
    fun providerOverflowDeleteDispatchesConfirmationRequest() {
        val providerId = "provider-delete"
        val deletedProvider = AtomicReference<String>()

        rule.setContent {
            NexaraTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = providerId,
                                name = "Delete Provider",
                                typeName = ProtocolType.Anthropic_Messages.displayName,
                                baseUrl = "https://api.example.com/v1",
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(
                        onRequestDeleteProvider = deletedProvider::set,
                    ),
                )
            }
        }

        rule.onNodeWithTag(
            UiTags.settingsProviderActions(providerId),
            useUnmergedTree = true,
        ).performClick()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:delete")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(deletedProvider.get()).isEqualTo(providerId)
    }

    @Test
    fun providerLongNameReflowsAt360DpAnd2xFontScale() {
        val longName =
            "OpenAI Compatible Internal Aggregator for International Production Workspaces"

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                NexaraTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        ProviderListScreenContent(
                            state = ProviderListScreenState(
                                providers = listOf(
                                    ProviderListItem(
                                        id = "provider-long-name",
                                        name = longName,
                                        typeName = ProtocolType.Generic_OpenAI_Compat.displayName,
                                        baseUrl = "https://internal.example.com/v1",
                                    ),
                                ),
                            ),
                            actions = ProviderListScreenActions(),
                        )
                    }
                }
            }
        }

        rule.onNodeWithText(longName, useUnmergedTree = true)
            .assertWidthIsAtLeast(220.dp)
            .assertHeightIsAtLeast(96.dp)
    }

    @Test
    fun providerEmptyStateKeepsOneAddAction() {
        rule.setContent {
            NexaraTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_provider_empty))
            .assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.SETTINGS_ADD_PROVIDER)
            .assertCountEquals(1)
    }
}
