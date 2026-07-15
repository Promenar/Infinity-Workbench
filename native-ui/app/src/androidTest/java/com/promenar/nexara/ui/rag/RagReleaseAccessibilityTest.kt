package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.ui.chat.components.FileBatchOperationResult
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.KgStatusIcon
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class RagReleaseAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun indexAndKnowledgeGraphStatusesExposeLocalizedTextAndStateDescriptions() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val fileStatuses = listOf(
            FileIndexStatus.INDEXED to R.string.rag_status_ready,
            FileIndexStatus.INDEXING to R.string.rag_status_indexing,
            FileIndexStatus.NOT_INDEXED to R.string.rag_status_pending,
            FileIndexStatus.FAILED to R.string.rag_status_error,
        )

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                Column {
                    fileStatuses.forEach { (status, _) ->
                        IndexStatusBadge(
                            status = status,
                            modifier = Modifier.testTag("file-status-${status.name}"),
                        )
                    }
                    KgStatusIcon(
                        status = KgStatus.NOT_STARTED,
                        modifier = Modifier.testTag("kg-status-not-started"),
                    )
                }
            }
        }

        fileStatuses.forEach { (status, labelResource) ->
            val label = resources.getString(labelResource)
            rule.onNodeWithTag("file-status-${status.name}")
                .assertTextEquals(label)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        label,
                    ),
                )
        }

        val graphDescription = resources.getString(
            R.string.kg_title,
        ) + ": " + resources.getString(R.string.rag_status_pending)
        rule.onNodeWithTag("kg-status-not-started")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    graphDescription,
                ),
            )
    }

    @Test
    fun releaseNavigationAndPrimaryActionsUseStableTags() {
        val configClicks = AtomicInteger(0)
        val graphClicks = AtomicInteger(0)
        val memoryLoads = AtomicInteger(0)
        val uploadClicks = AtomicInteger(0)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                var state by remember { mutableStateOf(releaseState()) }
                RagHomeScreenContent(
                    state = state,
                    actions = RagHomeScreenActions(
                        onChangeTab = { state = state.copy(currentTab = it) },
                        onLoadMemory = { memoryLoads.incrementAndGet() },
                        onOpenConfig = { configClicks.incrementAndGet() },
                        onOpenGraph = { graphClicks.incrementAndGet() },
                        onOpenFilePicker = { uploadClicks.incrementAndGet() },
                    ),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_CONFIG).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_UPLOAD).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_MEMORY).assertHasClickAction().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.RAG_HOME_MEMORY_CONTENT).assertExists()
        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_GRAPH).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_DOCUMENTS).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_NEW_FOLDER).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_NEW_FOLDER_DIALOG).assertExists()

        assertThat(configClicks.get()).isEqualTo(1)
        assertThat(uploadClicks.get()).isEqualTo(1)
        assertThat(memoryLoads.get()).isEqualTo(1)
        assertThat(graphClicks.get()).isEqualTo(1)
    }

    @Test
    fun selectionBarClearsReindexesAndRetainsOnlyFailedDeletes() {
        val selections = mutableStateListOf("doc-a", "doc-b")
        val reindexed = AtomicReference<List<String>>(emptyList())
        val deleteCalls = AtomicInteger(0)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(selectedIds = selections),
                    actions = RagHomeScreenActions(
                        onReindexDocuments = {
                            reindexed.set(it.toList())
                        },
                        onDeleteDocuments = { ids, onComplete ->
                            if (deleteCalls.incrementAndGet() == 1) {
                                onComplete(false, listOf(ids.last()))
                            } else {
                                onComplete(true, emptyList())
                            }
                        },
                    ),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_SELECTION_BAR).assertExists()
        rule.onNodeWithTag(UiTags.RAG_HOME_CLEAR_SELECTION).assertHasClickAction().performClick()
        rule.runOnIdle { assertThat(selections).isEmpty() }
        rule.onNodeWithTag(UiTags.RAG_HOME_SELECTION_BAR).assertDoesNotExist()

        rule.runOnIdle { selections.addAll(listOf("doc-a", "doc-b")) }
        rule.onNodeWithTag(UiTags.RAG_HOME_REINDEX_SELECTION).assertHasClickAction().performClick()
        rule.runOnIdle {
            assertThat(reindexed.get()).containsExactly("doc-a", "doc-b").inOrder()
            assertThat(selections).isEmpty()
            selections.addAll(listOf("doc-a", "doc-b"))
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_SELECTION).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG).assertExists()
        rule.runOnIdle { assertThat(deleteCalls.get()).isEqualTo(0) }
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON).assertHasClickAction().performClick()
        rule.runOnIdle { assertThat(selections).containsExactly("doc-b") }

        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_SELECTION).performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON).performClick()
        rule.runOnIdle {
            assertThat(deleteCalls.get()).isEqualTo(2)
            assertThat(selections).isEmpty()
        }
    }

    @Test
    fun selectionMoveHasSingleTaggedEntryAndOpensMoveSheet() {
        val selections = mutableStateListOf("doc-a")
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(
                        selectedIds = selections,
                        folders = listOf(Folder(id = "folder-a", name = "Archive")),
                    ),
                    actions = RagHomeScreenActions(),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_MOVE_SELECTION).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_MOVE_SHEET).assertExists()
    }

    @Test
    fun documentSlotDeleteRequestCannotBypassConfirmation() {
        val deleteCalls = AtomicInteger(0)
        val completion = AtomicReference<FileBatchOperationResult?>(null)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState(),
                    actions = RagHomeScreenActions(
                        onDeleteDocuments = { _, onComplete ->
                            deleteCalls.incrementAndGet()
                            onComplete(true, emptyList())
                        },
                    ),
                    documentsContent = { modifier, _, requestDelete ->
                        TextButton(
                            onClick = {
                                requestDelete(listOf("doc-a")) { result -> completion.set(result) }
                            },
                            modifier = modifier,
                        ) {
                            Text("request-delete")
                        }
                    },
                )
            }
        }

        rule.onNodeWithText("request-delete").assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG).assertExists()
        rule.runOnIdle { assertThat(deleteCalls.get()).isEqualTo(0) }

        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON).performClick()
        rule.runOnIdle {
            assertThat(deleteCalls.get()).isEqualTo(1)
            assertThat(completion.get()?.attemptedIds).containsExactly("doc-a")
            assertThat(completion.get()?.failedIds).isEmpty()
        }
    }

    private fun releaseState() = RagHomeScreenState(
        currentTab = PortalTab.DOCUMENTS,
        searchQuery = "",
        selectedIds = mutableListOf(),
        workspaceRootUuid = null,
        folders = emptyList(),
        folderStats = emptyMap(),
        stats = RagStats(),
        memoryVectors = emptyList(),
        isIndexing = false,
        indexingProgress = 0f,
        canRetryLastFailedIndex = false,
        isRetryingLastFailedIndex = false,
        indexingFileIds = emptySet(),
        kgExtractionStates = emptyMap(),
    )
}
