package com.promenar.nexara.ui.rag

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class DocEditorInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingDoesNotRenderFakeEditor() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = DocEditorUiState(phase = DocEditorPhase.Loading),
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_LOADING).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
    }

    @Test
    fun loadErrorRetries() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = DocEditorUiState(
                        phase = DocEditorPhase.LoadError,
                        failureCode = DocEditorFailureCode.LoadFailed,
                    ),
                ),
                actions = DocEditorScreenActions(onRetryLoad = { retries.incrementAndGet() }),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_LOAD)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun dirtyTopBackCancelRetainsContentAndConfirmNavigates() {
        val navigations = AtomicInteger(0)
        rule.setContent { TestRoute(navigations = navigations) }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CANCEL).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertExists()
        assertThat(navigations.get()).isEqualTo(0)

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CONFIRM).performClick()
        assertThat(navigations.get()).isEqualTo(1)
    }

    @Test
    fun dirtySystemBackUsesTheSameDiscardConfirmation() {
        val navigations = AtomicInteger(0)
        rule.setContent { TestRoute(navigations = navigations) }

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertExists()
        assertThat(navigations.get()).isEqualTo(0)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CONFIRM).performClick()
        assertThat(navigations.get()).isEqualTo(1)
    }

    @Test
    fun saveErrorRetries() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.SaveError,
                        dirty = true,
                    ),
                ),
                actions = DocEditorScreenActions(
                    onRetrySave = { retries.incrementAndGet() },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_SAVE).performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun readyWithPendingIndexShowsWarningAndRetriesIndex() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState().copy(
                        indexQueueFailed = true,
                        indexPendingTargets = listOf(
                            com.promenar.nexara.domain.repository.RenameIndexTarget(
                                fileUuid = "doc",
                                targetHash = "hash-2",
                                targetEpoch = 456L,
                            ),
                        ),
                    ),
                ),
                actions = DocEditorScreenActions(
                    onRetryPendingIndex = { retries.incrementAndGet() },
                ),
            )
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.rag_index_retry_hint),
        ).assertIsDisplayed()
        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.shared_btn_retry),
        )
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun pendingIndexWarningIsSingleAndRetryableAcrossSaveConflictAndLoadError() {
        val retries = AtomicInteger(0)
        var editorState by mutableStateOf(
            readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true).withPendingIndex(),
        )
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(editorState = editorState),
                actions = DocEditorScreenActions(
                    onRetryPendingIndex = { retries.incrementAndGet() },
                ),
            )
        }
        val hint = rule.activity.getString(com.promenar.nexara.R.string.rag_index_retry_hint)
        val retry = rule.activity.getString(com.promenar.nexara.R.string.shared_btn_retry)

        rule.onAllNodesWithText(hint).assertCountEquals(1)
        rule.onAllNodesWithText(retry).assertCountEquals(1)
        rule.onNodeWithText(retry).performClick()
        assertThat(retries.get()).isEqualTo(1)

        rule.runOnIdle {
            editorState = DocEditorUiState(
                phase = DocEditorPhase.LoadError,
                workspaceRootUuid = "root",
                documentId = "doc",
                failureCode = DocEditorFailureCode.LoadFailed,
            ).withPendingIndex()
        }
        rule.onAllNodesWithText(hint).assertCountEquals(1)
        rule.onAllNodesWithText(retry).assertCountEquals(1)
    }

    @Test
    fun compactDoubleFontConflictKeepsBothConflictActionsAndSinglePendingRetryVisible() {
        val copies = AtomicInteger(0)
        val reloads = AtomicInteger(0)
        val pendingRetries = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 640.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveConflict,
                                dirty = true,
                            ).withPendingIndex(),
                        ),
                        actions = DocEditorScreenActions(
                            onCopyLocalContent = { copies.incrementAndGet() },
                            onRequestReload = { reloads.incrementAndGet() },
                            onRetryPendingIndex = { pendingRetries.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.doc_editor_conflict_description),
        ).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_REQUEST_RELOAD)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        val pendingHint = rule.activity.getString(com.promenar.nexara.R.string.rag_index_retry_hint)
        val pendingRetry = rule.activity.getString(com.promenar.nexara.R.string.shared_btn_retry)
        rule.onAllNodesWithText(pendingHint).assertCountEquals(1)
        rule.onNodeWithText(pendingHint).assertIsDisplayed()
        rule.onAllNodesWithText(pendingRetry).assertCountEquals(1)
        rule.onNodeWithText(pendingRetry)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(copies.get()).isEqualTo(1)
        assertThat(reloads.get()).isEqualTo(1)
        assertThat(pendingRetries.get()).isEqualTo(1)
    }

    @Test
    fun saveCancelledShowsRetainedLocalChangesFeedback() {
        val retries = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveError,
                                dirty = true,
                            ).copy(failureCode = DocEditorFailureCode.SaveCancelled),
                        ),
                        actions = DocEditorScreenActions(
                            onRetrySave = { retries.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.doc_editor_save_cancelled_description),
        ).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_SAVE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun conflictAtDoubleFontScaleCopiesThenConfirmsReload() {
        val copies = AtomicInteger(0)
        val reloads = AtomicInteger(0)
        rule.setContent {
            var state by remember {
                mutableStateOf(
                    DocEditorScreenState(
                        editorState = readyEditorState(
                            phase = DocEditorPhase.SaveConflict,
                            dirty = true,
                        ),
                    ),
                )
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = state,
                        actions = DocEditorScreenActions(
                            onCopyLocalContent = { copies.incrementAndGet() },
                            onRequestReload = {
                                state = state.copy(confirmation = DocEditorConfirmation.ReloadLatest)
                            },
                            onDismissConfirmation = { state = state.copy(confirmation = null) },
                            onConfirmReload = {
                                state = state.copy(confirmation = null)
                                reloads.incrementAndGet()
                            },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_REQUEST_RELOAD)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RELOAD_DIALOG).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RELOAD_CONFIRM).performClick()
        assertThat(copies.get()).isEqualTo(1)
        assertThat(reloads.get()).isEqualTo(1)
    }

    @Test
    fun titleConflictUsesDedicatedWorkspaceAndRetryActions() {
        val useWorkspace = AtomicInteger(0)
        val retryMine = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveConflict,
                                dirty = true,
                            ).copy(
                                failureCode = DocEditorFailureCode.TitleConflict,
                                titleConflictCurrentName = "remote.md",
                            ),
                        ),
                        actions = DocEditorScreenActions(
                            onUseWorkspaceTitle = { useWorkspace.incrementAndGet() },
                            onRetryMyTitle = { retryMine.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_USE_WORKSPACE_TITLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_MY_TITLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(useWorkspace.get()).isEqualTo(1)
        assertThat(retryMine.get()).isEqualTo(1)
    }

    @Test
    fun savingPreventsRepeatSave() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.Saving,
                        dirty = true,
                    ),
                ),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVING).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertDoesNotExist()
    }

    @Test
    fun savingTopBackStaysOnEditorWithoutDiscardAndShowsVisibleFeedback() {
        val navigations = AtomicInteger(0)
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertDoesNotExist()
        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.doc_editor_saving_back_feedback),
        )
            .assertIsDisplayed()
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun savingSystemBackStaysOnEditorWithoutDiscard() {
        val navigations = AtomicInteger(0)
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                ),
            )
        }

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertDoesNotExist()
        rule.onNodeWithText(
            rule.activity.getString(com.promenar.nexara.R.string.doc_editor_saving_back_feedback),
        )
            .assertIsDisplayed()
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun rapidSavingBackUsesOnePoliteFeedbackCycleAndPhaseChangeDismissesIt() {
        val navigations = AtomicInteger(0)
        var editorState by mutableStateOf(
            readyEditorState(
                phase = DocEditorPhase.Saving,
                dirty = true,
            ),
        )
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = editorState,
            )
        }
        val feedback = rule.activity.getString(
            com.promenar.nexara.R.string.doc_editor_saving_back_feedback,
        )

        rule.runOnIdle {
            repeat(6) {
                rule.activity.onBackPressedDispatcher.onBackPressed()
            }
        }

        rule.onAllNodesWithText(feedback).assertCountEquals(1)
        rule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        ).assertCountEquals(0)
        rule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ) and SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
        ).assertCountEquals(1)
        assertThat(navigations.get()).isEqualTo(0)

        listOf(
            readyEditorState(dirty = false),
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true)
                .copy(failureCode = DocEditorFailureCode.SaveCancelled),
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true),
            readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true),
        ).forEach { nextState ->
            rule.runOnIdle { editorState = nextState }
            rule.onAllNodesWithText(feedback).assertCountEquals(0)

            rule.runOnIdle {
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                )
            }
            rule.runOnIdle {
                repeat(3) {
                    rule.activity.onBackPressedDispatcher.onBackPressed()
                }
            }
            rule.onAllNodesWithText(feedback).assertCountEquals(1)
        }

        rule.runOnIdle { editorState = readyEditorState(dirty = false) }
        rule.onAllNodesWithText(feedback).assertCountEquals(0)
    }

    @Test
    fun documentKeyChangeCancelsPreviousSavingFeedbackCycle() {
        val navigations = AtomicInteger(0)
        var stateKey by mutableStateOf("doc-a")
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                DocEditorRouteContent(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.Saving,
                        dirty = true,
                    ),
                    onNavigateBack = { navigations.incrementAndGet() },
                    actions = DocEditorScreenActions(),
                    stateKey = stateKey,
                )
            }
        }
        val feedback = rule.activity.getString(
            com.promenar.nexara.R.string.doc_editor_saving_back_feedback,
        )

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onAllNodesWithText(feedback).assertCountEquals(1)

        rule.runOnIdle { stateKey = "doc-b" }

        rule.onAllNodesWithText(feedback).assertCountEquals(0)
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun largeFileHidesEditingActions() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState().copy(
                        content = "",
                        persistedContent = "",
                        totalLines = 0,
                        sizeBytes = 1_048_577L,
                        isLargeFile = true,
                    ),
                ),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_LARGE_FILE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_WARNING_DISMISS)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
    }

    @Test
    fun phoneAtDoubleFontScaleKeepsPrimaryActionsReachableAndAtLeast48Dp() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(dirty = true),
                        ),
                    )
                }
            }
        }

        listOf(
            UiTags.DOC_EDITOR_BACK,
            UiTags.DOC_EDITOR_SAVE,
            UiTags.DOC_EDITOR_MODE_EDIT,
            UiTags.DOC_EDITOR_MODE_PREVIEW,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertIsEnabled()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT)
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    @Test
    fun splitAppearsOnlyAtOrAbove840FixtureWidth() {
        var width by mutableStateOf(840.dp)
        rule.setContent {
            Box(Modifier.requiredSize(width = width, height = 900.dp)) {
                TestContent(
                    screenState = DocEditorScreenState(editorState = readyEditorState()),
                )
            }
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        rule.runOnIdle { width = 719.dp }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    @Composable
    private fun TestRoute(
        navigations: AtomicInteger,
        editorState: DocEditorUiState = readyEditorState(dirty = true),
    ) {
        NexaraTheme(dynamicColor = false) {
            DocEditorRouteContent(
                editorState = editorState,
                onNavigateBack = { navigations.incrementAndGet() },
                actions = DocEditorScreenActions(),
            )
        }
    }

    @Composable
    private fun TestContent(
        screenState: DocEditorScreenState,
        actions: DocEditorScreenActions = DocEditorScreenActions(),
    ) {
        NexaraTheme(dynamicColor = false) {
            DocEditorScreenContent(state = screenState, actions = actions)
        }
    }

    private fun readyEditorState(
        phase: DocEditorPhase = DocEditorPhase.Ready,
        dirty: Boolean = false,
    ) = DocEditorUiState(
        phase = phase,
        workspaceRootUuid = "root",
        documentId = "doc",
        title = "release-notes.md",
        content = "# Release\n\nLocal draft",
        persistedTitle = "release-notes.md",
        persistedContent = if (dirty) "# Release" else "# Release\n\nLocal draft",
        currentHash = "hash-1",
        totalLines = 3,
        lastModified = 123L,
        sizeBytes = 256L,
        contentDirty = dirty,
        hasLoadedDocument = true,
        failureCode = when (phase) {
            DocEditorPhase.SaveError -> DocEditorFailureCode.ContentSaveFailed
            DocEditorPhase.SaveConflict -> DocEditorFailureCode.ContentConflict
            else -> null
        },
    )

    private fun DocEditorUiState.withPendingIndex() = copy(
        indexQueueFailed = true,
        indexPendingTargets = listOf(
            com.promenar.nexara.domain.repository.RenameIndexTarget(
                fileUuid = "doc",
                targetHash = "hash-2",
                targetEpoch = 456L,
            ),
        ),
    )
}
