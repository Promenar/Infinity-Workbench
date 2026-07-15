package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class ProviderModelsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun syncNoticeIsAssertiveAndDismissTargetIsAtLeast48Dp() {
        rule.setContent {
            NexaraTheme {
                ModelSyncNoticeBanner(
                    notice = ModelSyncNotice.syncFailed("must never be spoken"),
                    dismissEnabled = true,
                    onDismiss = {},
                )
            }
        }

        val message = resources.getString(R.string.provider_models_sync_failed)
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, message),
        )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_NOTICE_DISMISS)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun disabledSyncActionKeepsAccessibleTouchTarget() {
        rule.setContent {
            NexaraTheme {
                ActionChip(
                    icon = Icons.Rounded.Sync,
                    label = resources.getString(R.string.provider_models_auto_fetch),
                    enabled = false,
                    onClick = {},
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.provider_models_auto_fetch))
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun modelTypeCapabilityAndContextKeepDenseVisualsInsideAccessibleTargets() {
        val modelId = "provider-alpha::model-a"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                NexaraTheme {
                    Box(Modifier.width(360.dp)) {
                        EnhancedModelCard(
                            model = ModelInfo(
                                name = "Model A",
                                id = modelId,
                                description = "desc",
                                enabled = true,
                                type = "chat",
                                contextLength = 65_536,
                                capabilities = listOf("chat", "vision"),
                                providerId = "provider-alpha",
                            ),
                            testState = ModelTestState.Idle,
                            onUpdate = {},
                            onToggle = {},
                            onTest = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.providerModelsTypeAction(modelId, "chat"))
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.providerModelsTypeVisual(modelId, "chat"),
            useUnmergedTree = true,
        )
            .assertHeightIsEqualTo(36.dp)
        rule.onNodeWithTag(UiTags.providerModelsCapabilityAction(modelId, "vision"))
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.providerModelsCapabilityVisual(modelId, "vision"),
            useUnmergedTree = true,
        )
            .assertHeightIsEqualTo(36.dp)
        rule.onNodeWithTag(UiTags.providerModelsContextField(modelId))
            .assertWidthIsEqualTo(112.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun providerActionsStayTwoByTwoAndClickableAt360DpWithTwoXFontScale() {
        val clicks = AtomicInteger()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                NexaraTheme {
                    Box(Modifier.width(360.dp)) {
                        ProviderModelsActionsGrid(
                            isFetching = false,
                            rotation = 0f,
                            onRefresh = { clicks.incrementAndGet() },
                            onAdd = { clicks.incrementAndGet() },
                            onDisableAll = { clicks.incrementAndGet() },
                            onDeleteAll = { clicks.incrementAndGet() },
                        )
                    }
                }
            }
        }

        val labels = listOf(
            R.string.provider_models_auto_fetch,
            R.string.provider_models_add,
            R.string.provider_models_disable_all,
            R.string.provider_models_delete_all,
        ).map(resources::getString)
        val bounds = labels.map { label ->
            rule.onNodeWithText(label)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
                .fetchSemanticsNode().boundsInRoot
        }

        assertThat(bounds[0].top).isEqualTo(bounds[1].top)
        assertThat(bounds[2].top).isEqualTo(bounds[3].top)
        assertThat(bounds[2].top).isAtLeast(bounds[0].bottom)
        assertThat(bounds.all { it.left >= 0f && it.right <= 360f }).isTrue()
        assertThat(clicks.get()).isEqualTo(4)
    }

    @Test
    fun failedCustomModelSubmissionKeepsFormOpenAndPreservesInput() {
        var submittedId: String? = null
        var submittedName: String? = null
        var added = 0
        rule.setContent {
            NexaraTheme {
                AddCustomModelForm(
                    onSubmit = { id, name ->
                        submittedId = id
                        submittedName = name
                        false
                    },
                    onAdded = { added++ },
                )
            }
        }

        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_ID_FIELD, "custom-model")
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD, "Custom Name")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON).performClick()

        assertThat(submittedId).isEqualTo("custom-model")
        assertThat(submittedName).isEqualTo("Custom Name")
        assertThat(added).isEqualTo(0)
        rule.onNodeWithText("custom-model").assertExists()
        rule.onNodeWithText("Custom Name").assertExists()
        rule.onNodeWithText(resources.getString(R.string.generation_failure_invalid_request)).assertExists()
    }

    @Test
    fun providerModelsContentSupportsFullInteractionFlowByUniqueTags() {
        val refreshCount = AtomicInteger()
        val addCount = AtomicInteger()
        val disableAllCount = AtomicInteger()
        val deleteCount = AtomicInteger()
        val toggleCount = AtomicInteger()
        val testCount = AtomicInteger()
        val deleteAllConfirmCount = AtomicInteger()
        val submittedId = AtomicReference<String?>(null)

        rule.setContent {
            NexaraTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider-Alpha",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Model A",
                                id = "provider-alpha::model-a",
                                description = "Description A",
                                enabled = true,
                                type = "chat",
                                contextLength = 4096,
                                capabilities = listOf("chat", "vision"),
                                providerId = "provider-alpha",
                                maxOutputTokens = 2048,
                                knowledgeCutoff = "20240101",
                            ),
                        ),
                        modelTestStates = mapOf("provider-alpha::model-a" to ModelTestState.Idle),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = { refreshCount.incrementAndGet() },
                        onAdd = { id, name ->
                            submittedId.set("$id|$name")
                            addCount.incrementAndGet()
                            true
                        },
                        onDisableAll = { disableAllCount.incrementAndGet() },
                        onDeleteAll = { deleteAllConfirmCount.incrementAndGet() },
                        onUpdate = {},
                        onToggle = { toggleCount.incrementAndGet() },
                        onTest = { testCount.incrementAndGet() },
                        onCancelTest = {},
                        onDelete = { deleteCount.incrementAndGet() },
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_SYNC).performClick()
        assertThat(refreshCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_ADD).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SHEET).assertExists()
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_ID_FIELD, "provider-alpha::added-model")
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD, "Added Model")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON).performClick()
        assertThat(addCount.get()).isEqualTo(1)
        assertThat(submittedId.get()).isEqualTo("provider-alpha::added-model|Added Model")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SHEET).assertDoesNotExist()

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL).performClick()
        assertThat(disableAllCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON).performClick()
        assertThat(deleteAllConfirmCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.providerModelsModelCard("provider-alpha::model-a")).performScrollTo()
        rule.onNodeWithTag(UiTags.providerModelsTestAction("provider-alpha::model-a")).performClick()
        assertThat(testCount.get()).isEqualTo(1)
        rule.onNodeWithTag(UiTags.providerModelsDeleteAction("provider-alpha::model-a")).performClick()
        assertThat(deleteCount.get()).isEqualTo(1)
        rule.onNodeWithTag(UiTags.providerModelsToggleAction("provider-alpha::model-a")).performClick()
        assertThat(toggleCount.get()).isEqualTo(1)
    }

    @Test
    fun providerModelsNoticeAndStateMessagesExposeDistinctTags() {
        rule.setContent {
            NexaraTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = true,
                        syncNotice = ModelSyncNotice.loading(),
                        models = emptyList(),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_NOTICE).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_LOADING).assertExists()
    }

    @Test
    fun providerModelsContentCanRenderModelActionTagsByModelId() {
        val modelId = "provider-alpha::long-id-with-dash-123"
        val cancelTestCount = AtomicInteger(0)
        rule.setContent {
            NexaraTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Model Long Name",
                                id = modelId,
                                description = "desc",
                                enabled = false,
                                type = "chat",
                                providerId = "p",
                            ),
                        ),
                        modelTestStates = mapOf(modelId to ModelTestState.Testing),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = { cancelTestCount.incrementAndGet() },
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.providerModelsModelCard(modelId))
            .assertExists()
            .performScrollTo()
        rule.onNodeWithTag(UiTags.providerModelsTestAction(modelId))
            .assertExists()
            .performClick()
        assertThat(cancelTestCount.get()).isEqualTo(1)
        rule.onNodeWithTag(UiTags.providerModelsDeleteAction(modelId)).assertExists()
        rule.onNodeWithTag(UiTags.providerModelsToggleAction(modelId)).assertExists()
    }

    @Test
    fun enhancedModelCardSynchronizesDraftWhenSameModelIdIsRefreshed() {
        val modelState = mutableStateOf(
            ModelInfo(
                name = "Initial Model",
                id = "provider-alpha::stable-id",
                description = "desc",
                enabled = true,
                type = "chat",
                providerId = "p",
                contextLength = 8_192,
                capabilities = listOf("chat"),
            ),
        )
        rule.setContent {
            NexaraTheme {
                EnhancedModelCard(
                    model = modelState.value,
                    testState = ModelTestState.Idle,
                    onUpdate = {},
                    onToggle = {},
                    onTest = {},
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithText("Initial Model").assertExists()
        rule.runOnIdle {
            modelState.value = modelState.value.copy(
                name = "Refreshed Model",
                type = "reasoning",
                contextLength = 262_144,
                capabilities = listOf("reasoning", "vision"),
            )
        }

        rule.onNodeWithText("Refreshed Model").assertExists()
        rule.onNodeWithText("Initial Model").assertDoesNotExist()
    }

    @Test
    fun providerModelsActionListTagRemainsDiscoverableWhenEmptyAndSearchState() {
        rule.setContent {
            NexaraTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = false,
                        syncNotice = null,
                        models = emptyList(),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_LIST).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_EMPTY).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD).assertExists()
    }

    private fun inputInTaggedSearch(tag: String, value: String) {
        rule.onNode(
            matcher = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true,
        ).performTextInput(value)
    }
}
