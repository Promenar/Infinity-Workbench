package com.promenar.nexara.ui.rag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreenActions
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreenContent
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreenState
import com.promenar.nexara.ui.settings.SearchConfigState
import com.promenar.nexara.ui.settings.SearchSecretOperation
import com.promenar.nexara.ui.settings.SearchSecretErrorCode
import com.promenar.nexara.ui.settings.TavilySecretEditor
import com.promenar.nexara.ui.settings.TavilySecretActions
import com.promenar.nexara.ui.settings.EngineOption
import com.promenar.nexara.ui.settings.DepthChip
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Rule
import org.junit.Test

class RagSettingsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun globalRagPresetsSlidersAndClearConfirmationUseProductionContentAt2x() {
        val selectedPreset = AtomicReference<String>()
        val clearedWithGraph = AtomicReference<Boolean>()
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var state by mutableStateOf(
            GlobalRagConfigScreenState(
                config = RagConfiguration(currentPreset = "balanced"),
            ),
        )
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    GlobalRagConfigScreenContent(
                        state = state,
                        actions = GlobalRagConfigScreenActions(
                            onPresetSelected = { preset ->
                                selectedPreset.set(preset)
                                state = state.copy(
                                    config = state.config.copy(currentPreset = preset),
                                )
                            },
                            onConfigChanged = { transform ->
                                state = state.copy(config = state.config.transform())
                            },
                            onClearVectors = clearedWithGraph::set,
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag("rag_global_presets")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("rag_global_chunk_size_slider")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertThat(setProgress(500f)).isTrue()
            }
        rule.runOnIdle { assertThat(state.config.docChunkSize).isEqualTo(500) }
        rule.onNodeWithText(resources.getString(R.string.rag_config_preset_coding)).performClick()
        rule.runOnIdle { assertThat(selectedPreset.get()).isEqualTo("coding") }
        rule.onNodeWithTag("rag_global_preset_coding").assertIsSelected()

        val presetTop = rule.onNodeWithTag("rag_global_presets")
            .fetchSemanticsNode().boundsInRoot.top
        val sliderTop = rule.onNodeWithTag("rag_global_chunk_size_slider")
            .fetchSemanticsNode().boundsInRoot.top
        assertThat(presetTop).isLessThan(sliderTop)

        rule.onNodeWithTag("rag_global_clear_action")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag("rag_global_clear_graph_row", useUnmergedTree = true)
            .assertIsSelectable()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_graph_radio", useUnmergedTree = true)
            .assert(!hasClickAction())
        rule.onNodeWithTag("rag_global_clear_vectors_row", useUnmergedTree = true)
            .assertIsSelectable()
            .assertHasClickAction()
            .performClick()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_vectors_radio", useUnmergedTree = true)
            .assert(!hasClickAction())
        rule.onNodeWithTag("rag_global_clear_graph_row", useUnmergedTree = true)
            .assertHasClickAction()
            .performClick()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_confirm", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertThat(clearedWithGraph.get()).isTrue() }
    }

    @Test
    fun tavilySecretEditorSavesRevealsAndClearsWithoutPersistingPlaintextInState() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var state by mutableStateOf(
            SearchConfigState(
                searchEngine = "tavily",
                hasTavilyApiKey = false,
                secretOperation = SearchSecretOperation.Idle,
            ),
        )
        val savedSecret = AtomicReference<String>()
        val cleared = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                TavilySecretEditor(
                    state = state,
                    actions = TavilySecretActions(
                        onSave = { secret ->
                            savedSecret.set(secret.concatToString())
                            state = state.copy(
                                hasTavilyApiKey = true,
                                secretOperation = SearchSecretOperation.Saved,
                            )
                        },
                        onReveal = { "runtime-only-key".toCharArray() },
                        onClear = {
                            cleared.set(true)
                            state = state.copy(
                                hasTavilyApiKey = false,
                                secretOperation = SearchSecretOperation.Idle,
                            )
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("search_tavily_secret_field").performTextInput("test-secret")
        rule.onNodeWithTag("search_tavily_secret_save")
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle {
            assertThat(savedSecret.get()).isEqualTo("test-secret")
            assertThat(state).isEqualTo(
                SearchConfigState(
                    searchEngine = "tavily",
                    hasTavilyApiKey = true,
                    secretOperation = SearchSecretOperation.Saved,
                ),
            )
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_show))
            .assertHasClickAction()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search_tavily_secret_field").assertTextContains("runtime-only-key")
        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_clear))
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle {
            assertThat(cleared.get()).isTrue()
            assertThat(state.hasTavilyApiKey).isFalse()
        }
    }

    @Test
    fun tavilySaveFailureRetainsEditAndRetryReplaysOnlySave() {
        var state by mutableStateOf(
            SearchConfigState(
                searchEngine = "tavily",
                secretOperation = SearchSecretOperation.Idle,
            ),
        )
        val retried = AtomicReference<String>()
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                TavilySecretEditor(
                    state = state,
                    actions = TavilySecretActions(
                        onSave = {
                            state = state.copy(
                                secretOperation = SearchSecretOperation.Error(
                                    SearchSecretErrorCode.SAVE_FAILED,
                                ),
                            )
                        },
                        onRetry = { code, secret ->
                            assertThat(code).isEqualTo(SearchSecretErrorCode.SAVE_FAILED)
                            retried.set(secret?.concatToString())
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("search_tavily_secret_field").performTextInput("retry-secret")
        rule.onNodeWithTag("search_tavily_secret_save").performClick()
        rule.onNodeWithTag("search_tavily_secret_field").assertTextContains("retry-secret")
        rule.onNodeWithTag("search_tavily_secret_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle { assertThat(retried.get()).isEqualTo("retry-secret") }
    }

    @Test
    fun searchEngineAndDepthOptionsExposeSelectableRadioSemantics() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    EngineOption("tavily", "Tavily", true) {}
                    DepthChip("advanced", "Advanced", true) {}
                }
            }
        }

        rule.onNodeWithTag("search_engine_tavily")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("search_depth_advanced")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun advancedRetrievalUsesAtomicTransformsAndSingleToggleNodes() {
        var state by mutableStateOf(
            AdvancedRetrievalScreenState(
                config = RagConfiguration(
                    enableHybridSearch = true,
                    enableQueryRewrite = true,
                    enableRerank = false,
                ),
                isRerankAvailable = true,
            ),
        )
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                AdvancedRetrievalScreenContent(
                    state = state,
                    actions = AdvancedRetrievalScreenActions(
                        onConfigChanged = { transform ->
                            state = state.copy(config = state.config.transform())
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("advanced_retrieval_memory_limit_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertThat(setProgress(20f)).isTrue()
            }
        rule.runOnIdle { assertThat(state.config.memoryLimit).isEqualTo(20) }
        rule.onNodeWithTag("advanced_retrieval_hybrid_toggle")
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .performClick()
        rule.runOnIdle { assertThat(state.config.enableHybridSearch).isFalse() }
        rule.onNodeWithTag("advanced_retrieval_hybrid_switch", useUnmergedTree = true)
            .assert(!hasClickAction())
        rule.onNodeWithTag("advanced_retrieval_rewrite_toggle")
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .performClick()
        rule.runOnIdle { assertThat(state.config.enableQueryRewrite).isFalse() }
        rule.onNodeWithTag("advanced_retrieval_rewrite_switch", useUnmergedTree = true)
            .assert(!hasClickAction())
    }

    @Test
    fun ragAdvancedDispatchesSliderAndGraphNavigationFromProductionContent() {
        var state by mutableStateOf(
            RagAdvancedScreenState(
                config = RagConfiguration(kgExtractionTimeoutSeconds = 120),
                allModels = emptyList(),
            ),
        )
        val navigated = AtomicBoolean(false)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagAdvancedScreenContent(
                    state = state,
                    actions = RagAdvancedScreenActions(
                        onConfigChanged = { transform ->
                            state = state.copy(config = state.config.transform())
                        },
                        onNavigateToGraph = { navigated.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag("rag_advanced_timeout_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertThat(setProgress(30f)).isTrue()
            }
        rule.runOnIdle { assertThat(state.config.kgExtractionTimeoutSeconds).isEqualTo(30) }
        rule.onNodeWithTag("rag_advanced_graph_row")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertThat(navigated.get()).isTrue() }
    }

    @Test
    fun agentRetrievalParameterAndResetActionsUseProductionContent() {
        val reset = AtomicBoolean(false)
        var state by mutableStateOf(
            AgentAdvancedRetrievalScreenState(
                scopeLabel = "Release assistant",
                useInheritedConfig = false,
                retrievalConfig = AgentRetrievalConfig(),
                isRerankAvailable = true,
            ),
        )
        rule.setContent {
            NexaraTheme(
                preferences = NexaraThemePreferences(
                    mode = NexaraThemeMode.LIGHT,
                    colorSource = NexaraColorSource.NEXARA,
                ),
            ) {
                AgentAdvancedRetrievalScreenContent(
                    state = state,
                    actions = AgentAdvancedRetrievalScreenActions(
                        onRetrievalConfigChanged = { transform ->
                            state = state.copy(
                                retrievalConfig = state.retrievalConfig.transform(),
                            )
                        },
                        onResetToGlobal = { reset.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag("agent_retrieval_memory_limit_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertThat(setProgress(8f)).isTrue()
            }
        rule.runOnIdle { assertThat(state.retrievalConfig.memoryLimit).isEqualTo(8) }
        val semanticOrder = buildList<String> {
            fun collect(node: SemanticsNode) {
                if (SemanticsProperties.TestTag in node.config) {
                    add(node.config[SemanticsProperties.TestTag])
                }
                node.children.forEach(::collect)
            }
            collect(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        }
        assertThat(semanticOrder.indexOf("agent_retrieval_rerank_toggle"))
            .isLessThan(semanticOrder.indexOf("agent_retrieval_rewrite_toggle"))
        assertThat(semanticOrder.indexOf("agent_retrieval_rewrite_toggle"))
            .isLessThan(semanticOrder.indexOf("agent_retrieval_hybrid_toggle"))
        assertAgentToggle(
            tag = "agent_retrieval_rerank_toggle",
            switchTag = "agent_retrieval_rerank_switch",
        )
        rule.onNodeWithTag("agent_retrieval_rerank_toggle").performClick()
        rule.runOnIdle { assertThat(state.retrievalConfig.enableRerank).isFalse() }
        assertAgentToggle(
            tag = "agent_retrieval_rewrite_toggle",
            switchTag = "agent_retrieval_rewrite_switch",
        )
        rule.onNodeWithTag("agent_retrieval_rewrite_toggle").performClick()
        rule.runOnIdle { assertThat(state.retrievalConfig.enableQueryRewrite).isFalse() }
        assertAgentToggle(
            tag = "agent_retrieval_hybrid_toggle",
            switchTag = "agent_retrieval_hybrid_switch",
        )
        rule.onNodeWithTag("agent_retrieval_hybrid_toggle").performClick()
        rule.runOnIdle { assertThat(state.retrievalConfig.enableHybridSearch).isFalse() }
        rule.onNodeWithTag("agent_retrieval_reset_action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag("agent_retrieval_reset_dialog").assertIsDisplayed()
        rule.onNodeWithTag("agent_retrieval_reset_confirm").performClick()
        rule.runOnIdle { assertThat(reset.get()).isTrue() }
    }

    private fun assertAgentToggle(
        tag: String,
        switchTag: String,
    ) {
        rule.onNodeWithTag(tag)
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            )
        rule.onNodeWithTag(switchTag, useUnmergedTree = true)
            .assert(!hasClickAction())
    }
}
