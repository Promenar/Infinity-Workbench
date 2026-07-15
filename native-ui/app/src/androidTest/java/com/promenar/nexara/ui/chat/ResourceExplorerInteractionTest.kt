package com.promenar.nexara.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class ResourceExplorerInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun selectingRecycleBinHidesFilesSearchAndUpdatesExplicitTabState() {
        var selectedTab by mutableStateOf(ResourceExplorerTab.Files)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(selectedTab = selectedTab),
                    actions = ResourceExplorerSheetActions(
                        onTabSelected = { selectedTab = it },
                    ),
                    filesContent = {},
                    recycleBinContent = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SEARCH).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_TAB_RECYCLE_BIN).performClick()
        rule.waitForIdle()

        assertThat(selectedTab).isEqualTo(ResourceExplorerTab.RecycleBin)
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SEARCH).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_TAB_RECYCLE_BIN).assertIsDisplayed()
    }

    @Test
    fun swipingPagerToRecycleBinUpdatesExternalTabAndRemovesSearch() {
        var selectedTab by mutableStateOf(ResourceExplorerTab.Files)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(selectedTab = selectedTab),
                    actions = ResourceExplorerSheetActions(
                        onTabSelected = { selectedTab = it },
                    ),
                    filesContent = {},
                    recycleBinContent = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_PAGER)
            .performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertThat(selectedTab).isEqualTo(ResourceExplorerTab.RecycleBin)
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SEARCH).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_TAB_RECYCLE_BIN).assertIsSelected()
    }

    @Test
    fun recycleBinLoadingStateDoesNotExposeFalseEmptyState() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(
                        selectedTab = ResourceExplorerTab.RecycleBin,
                        isLoading = true,
                    ),
                    actions = ResourceExplorerSheetActions(),
                    filesContent = {},
                    recycleBinContent = {
                        Box(Modifier.testTag(TAG_FALSE_EMPTY_STATE))
                    },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.shared_loading),
                ),
            )
        rule.onNodeWithTag(TAG_FALSE_EMPTY_STATE).assertDoesNotExist()
    }

    @Test
    fun recycleBinLoadErrorDoesNotExposeFalseEmptyStateAndRetriesOnce() {
        val retryCount = AtomicInteger()

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(
                        selectedTab = ResourceExplorerTab.RecycleBin,
                        hasLoadError = true,
                    ),
                    actions = ResourceExplorerSheetActions(
                        onRetryLoad = { retryCount.incrementAndGet() },
                    ),
                    filesContent = {},
                    recycleBinContent = {
                        Box(Modifier.testTag(TAG_FALSE_EMPTY_STATE))
                    },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.resource_explorer_error_workspace_load_failed),
                ),
            )
        rule.onNodeWithTag(TAG_FALSE_EMPTY_STATE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retryCount.get()).isEqualTo(1)
    }

    @Test
    fun clearResultsButtonCallsTheRealClearAction() {
        val clearCount = AtomicInteger()

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(importItems = listOf(importItem())),
                    actions = ResourceExplorerSheetActions(
                        onClearImportResults = { clearCount.incrementAndGet() },
                    ),
                    filesContent = {},
                    recycleBinContent = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.resource_explorer_clear_results))
            .assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(clearCount.get()).isEqualTo(1)
    }

    @Test
    fun phoneWithDoubleFontKeepsPrimaryControlsVisibleAndReachable() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                        ResourceExplorerSheetContent(
                            state = state(),
                            actions = ResourceExplorerSheetActions(),
                            filesContent = {},
                            recycleBinContent = {},
                        )
                    }
                }
            }
        }

        listOf(
            UiTags.RESOURCE_EXPLORER_SEARCH,
            UiTags.RESOURCE_EXPLORER_IMPORT,
            UiTags.RESOURCE_EXPLORER_TAB_FILES,
            UiTags.RESOURCE_EXPLORER_TAB_RECYCLE_BIN,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun state(
        selectedTab: ResourceExplorerTab = ResourceExplorerTab.Files,
        importItems: List<ShareImportItem> = emptyList(),
        isLoading: Boolean = false,
        hasLoadError: Boolean = false,
    ) = ResourceExplorerSheetState(
        selectedTab = selectedTab,
        searchQuery = "",
        recycleBinCount = 2,
        workspaceReady = true,
        importItems = importItems,
        isImporting = false,
        isLoading = isLoading,
        hasLoadError = hasLoadError,
    )

    private fun importItem() = ShareImportItem(
        uri = Uri.parse("content://fixture/very-long-import-item"),
        displayName = "A-very-long-import-file-name-that-must-not-overflow-the-sheet.md",
        mimeType = "text/markdown",
        sizeBytes = 1024L,
        status = ShareImportStatus.Created,
    )

    private companion object {
        const val TAG_FALSE_EMPTY_STATE = "recycle_bin_empty_state"
    }
}
