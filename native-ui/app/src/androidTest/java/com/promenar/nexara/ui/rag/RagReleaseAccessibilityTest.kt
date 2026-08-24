package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.ui.chat.components.FileBatchOperationResult
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.KgStatusIcon
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class RagReleaseAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun indexingProgressBarPublishesOneProgressStateAndLiveRegionWithoutAccessibleDisplayText() {
        val status = "Indexing release documents"
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                IndexingProgressBar(
                    progress = 0.42f,
                    statusText = status,
                    modifier = Modifier.testTag("indexing-progress-contract"),
                )
            }
        }

        assertSingleIndexingSemantics(
            scopeTag = "indexing-progress-contract",
            status = status,
            percentage = "42%",
            liveRegionMode = LiveRegionMode.Polite,
        )
    }

    @Test
    fun ragHomeIndexingNoticePublishesOneAccessibilityFactSource() {
        val status = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.rag_index_phase_pending)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(
                        isIndexing = true,
                        indexingProgress = 0.42f,
                        indexingNotice = UiStatusNotice(
                            severity = NoticeSeverity.Info,
                            code = IndexingNotice.CODE_PENDING,
                        ),
                    ),
                    actions = RagHomeScreenActions(),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        assertSingleIndexingSemantics(
            scopeTag = UiTags.RAG_HOME_INDEXING_NOTICE,
            status = status,
            percentage = "42%",
            liveRegionMode = LiveRegionMode.Polite,
        )
    }

    @Test
    fun ragFolderIndexingNoticePublishesOneAccessibilityFactSource() {
        val status = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.rag_index_phase_pending)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagFolderScreenContent(
                    state = ragFolderState(
                        selectedIds = remember { mutableStateListOf() },
                    ).copy(
                        isIndexing = true,
                        indexingProgress = 0.42f,
                        indexingNotice = UiStatusNotice(
                            severity = NoticeSeverity.Info,
                            code = IndexingNotice.CODE_PENDING,
                        ),
                    ),
                    actions = RagFolderScreenActions(),
                )
            }
        }

        assertSingleIndexingSemantics(
            scopeTag = UiTags.RAG_FOLDER_CONTENT,
            status = status,
            percentage = "42%",
            liveRegionMode = LiveRegionMode.Polite,
        )
    }

    @Test
    fun ragFolderDocumentRowExposesButtonRoleLocalizedActionAndIndependentCheckbox() {
        val clicked = AtomicInteger(0)
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val expectedOpenLabel = if (resources.configuration.locales[0].language == "zh") {
            "打开 Release checklist.md"
        } else {
            "Open Release checklist.md"
        }
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagDocItem(
                    title = "Release checklist.md",
                    status = RagStatus.READY,
                    showCheckbox = true,
                    onCheckedChange = {},
                    onClick = { clicked.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithTag("RAG_FOLDER_DOCUMENT_ITEM")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("document row has localized open label") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        expectedOpenLabel
                },
            )
            .performClick()
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
            useUnmergedTree = true,
        ).assertExists()
        rule.runOnIdle { assertThat(clicked.get()).isEqualTo(1) }
    }

    @Test
    fun ragFolderStatusChipHasStateDescriptionWithoutDuplicateTextSemantics() {
        val ready = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.rag_status_ready)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagDocItem(
                    title = "Release checklist.md",
                    status = RagStatus.READY,
                    onClick = {},
                )
            }
        }

        rule.onNodeWithTag("RAG_FOLDER_DOCUMENT_ITEM")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ready))
            .assert(
                SemanticsMatcher("document row does not duplicate status as text semantics") { node ->
                    runCatching { node.config[SemanticsProperties.Text] }
                        .getOrNull()
                        .orEmpty()
                        .none { it.text == ready }
                },
            )
    }

    @Test
    fun ragFolderLargeFontOverflowTogglesAllAndPrimaryCallbacksRemainReachable() {
        val backClicks = AtomicInteger(0)
        val uploadClicks = AtomicInteger(0)
        lateinit var selected: MutableList<String>
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    selected = remember { mutableStateListOf("doc-a", "doc-b") }
                    RagFolderScreenContent(
                        state = ragFolderState(selectedIds = selected),
                        actions = RagFolderScreenActions(
                            onBack = { backClicks.incrementAndGet() },
                            onUpload = { uploadClicks.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECT_ALL).performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECT_ALL_MENU_ITEM).assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(selected).isEmpty() }
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertDoesNotExist()

        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECT_ALL).performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECT_ALL_MENU_ITEM).assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(selected).containsExactly("doc-a", "doc-b").inOrder() }
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertIsDisplayed()

        rule.onNodeWithTag(UiTags.RAG_FOLDER_BACK).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_UPLOAD).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertThat(backClicks.get()).isEqualTo(1)
            assertThat(uploadClicks.get()).isEqualTo(1)
        }
    }

    @Test
    fun ragFolderLargeFontKeepsSelectionActionsReachableAndDeleteConfirmed() {
        val deleteCalls = AtomicInteger(0)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    val selected = remember { mutableStateListOf("doc-a", "doc-b") }
                    RagFolderScreenContent(
                        state = ragFolderState(selectedIds = selected),
                        actions = RagFolderScreenActions(
                            onDelete = { ids, complete ->
                                deleteCalls.incrementAndGet()
                                complete(false, listOf(ids.last()))
                            },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SELECTION).assertHasClickAction()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_REINDEX_SELECTION).assertHasClickAction()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_DELETE_SELECTION).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_DELETE_CONFIRM_DIALOG).assertExists()
        rule.runOnIdle { assertThat(deleteCalls.get()).isEqualTo(0) }
        rule.onNodeWithTag(UiTags.RAG_FOLDER_DELETE_CONFIRM_BUTTON).performClick()
        rule.runOnIdle { assertThat(deleteCalls.get()).isEqualTo(1) }
    }

    @Test
    fun ragFolderMoveSheetScrollsToLastOfTwentyFoldersAndRetainsFailure() {
        val movedTarget = AtomicReference<String?>(null)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                val selected = remember { mutableStateListOf("doc-a", "doc-b") }
                RagFolderScreenContent(
                    state = ragFolderState(
                        selectedIds = selected,
                        folders = (1..20).map { Folder("folder-$it", "Folder $it") },
                    ),
                    actions = RagFolderScreenActions(
                        onMove = { ids, target, complete ->
                            movedTarget.set(target)
                            complete(false, listOf(ids.last()))
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SELECTION).performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_LIST).performScrollToIndex(19)
        rule.onNodeWithText("Folder 20").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(movedTarget.get()).isEqualTo("folder-20") }
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertExists()
    }

    @Test
    fun ragFolderMoveSheetAtLargeFontScrollsClicksLastOfTwentyFolders() {
        val movedTarget = AtomicReference<String?>(null)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    val selected = remember { mutableStateListOf<String>() }
                    RagFolderScreenContent(
                        state = ragFolderState(
                            selectedIds = selected,
                            folders = (1..20).map { Folder("folder-$it", "Folder $it") },
                        ).copy(documents = listOf(ragFolderDocument("doc-a"))),
                        actions = RagFolderScreenActions(
                            onMove = { ids, target, complete ->
                                movedTarget.set(target)
                                complete(false, ids.toList())
                            },
                        ),
                    )
                }
            }
        }

        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
            useUnmergedTree = true,
        ).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SELECTION).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SHEET).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_LIST).assertIsDisplayed().performScrollToIndex(19)
        rule.onNodeWithText("Folder 20").assertIsDisplayed().assertHasClickAction().performClick()
        rule.runOnIdle { assertThat(movedTarget.get()).isEqualTo("folder-20") }
    }

    @Test
    fun ragFolderMoveSheetInLandscapeScrollsClicksLastOfTwentyFolders() {
        val movedTarget = AtomicReference<String?>(null)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                NexaraTheme(dynamicColor = false) {
                    val selected = remember { mutableStateListOf<String>() }
                    RagFolderScreenContent(
                        state = ragFolderState(
                            selectedIds = selected,
                            folders = (1..20).map { Folder("folder-$it", "Folder $it") },
                        ).copy(documents = listOf(ragFolderDocument("doc-a"))),
                        actions = RagFolderScreenActions(
                            onMove = { ids, target, complete ->
                                movedTarget.set(target)
                                complete(false, ids.toList())
                            },
                        ),
                    )
                }
            }
        }

        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
            useUnmergedTree = true,
        ).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECTION_BAR).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SELECTION).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_SHEET).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_MOVE_LIST).assertIsDisplayed().performScrollToIndex(19)
        rule.onNodeWithText("Folder 20").assertIsDisplayed().assertHasClickAction().performClick()
        rule.runOnIdle { assertThat(movedTarget.get()).isEqualTo("folder-20") }
    }

    @Test
    fun ragFolderLandscapeKeepsNavigationUploadAndDocumentOpenReachable() {
        val opened = AtomicReference<String?>(null)
        val backClicks = AtomicInteger(0)
        val uploadClicks = AtomicInteger(0)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RagFolderScreenContent(
                        state = ragFolderState(selectedIds = remember { mutableStateListOf() }),
                        actions = RagFolderScreenActions(
                            onBack = { backClicks.incrementAndGet() },
                            onUpload = { uploadClicks.incrementAndGet() },
                            onOpenDocument = { _, documentId -> opened.set(documentId) },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_FOLDER_ROOT).assertExists()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_SELECT_ALL).assertHasClickAction()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_BACK).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.RAG_FOLDER_UPLOAD).assertIsDisplayed().performClick()
        rule.onNodeWithText("doc-a release checklist with responsive typography.md").performClick()
        rule.runOnIdle {
            assertThat(backClicks.get()).isEqualTo(1)
            assertThat(uploadClicks.get()).isEqualTo(1)
            assertThat(opened.get()).isEqualTo("doc-a")
        }
    }

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
    fun searchIsOnlyShownForDocumentsAndGraphDoesNotChangeTab() {
        val graphClicks = AtomicInteger(0)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                var state by remember { mutableStateOf(releaseState()) }
                RagHomeScreenContent(
                    state = state,
                    actions = RagHomeScreenActions(
                        onChangeTab = { state = state.copy(currentTab = it) },
                        onOpenGraph = { graphClicks.incrementAndGet() },
                    ),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_SEARCH).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_GRAPH).performClick()
        rule.runOnIdle { assertThat(graphClicks.get()).isEqualTo(1) }
        rule.onNodeWithTag(UiTags.RAG_HOME_DOCUMENTS_CONTENT).assertExists()
        rule.onNodeWithTag(UiTags.RAG_HOME_SEARCH).assertIsDisplayed()

        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_MEMORY).performClick()
        rule.onNodeWithTag(UiTags.RAG_HOME_MEMORY_CONTENT).assertExists()
        rule.onNodeWithTag(UiTags.RAG_HOME_SEARCH).assertDoesNotExist()
    }

    @Test
    fun memoryContentExposesExpandAndDeleteThenConfirmsDeletion() {
        val deletedId = AtomicReference<String?>(null)
        val memory = MemoryVectorRecord(
            id = "memory-a",
            content = "A long memory with enough detail to verify the visible expansion control and the complete readable content after expansion. ".repeat(4),
            sessionId = "session-12345678",
            createdAt = 1_725_000_000_000,
        )
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(
                        currentTab = PortalTab.MEMORY,
                        memoryVectors = listOf(memory),
                    ),
                    actions = RagHomeScreenActions(onDeleteMemory = deletedId::set),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag("memory-expand-memory-a").assertHasClickAction().performClick()
        rule.onNodeWithText(memory.content).assertIsDisplayed()
        rule.onNodeWithTag("memory-delete-memory-a").assertHasClickAction().performClick()
        rule.onNodeWithTag("memory-delete-dialog").assertExists()
        rule.runOnIdle { assertThat(deletedId.get()).isNull() }
        rule.onNodeWithTag("memory-delete-confirm").assertHasClickAction().performClick()
        rule.runOnIdle { assertThat(deletedId.get()).isEqualTo("memory-a") }
    }

    @Test
    fun memoryRefreshKeepsDeleteConfirmationAndOriginalTarget() {
        val deletedId = AtomicReference<String?>(null)
        val originalMemory = MemoryVectorRecord(
            id = "memory-a",
            content = "Original memory selected for deletion",
            sessionId = "session-a",
            createdAt = 1_725_000_000_000,
        )
        val refreshedMemory = MemoryVectorRecord(
            id = "memory-b",
            content = "Memory arriving from an asynchronous refresh",
            sessionId = "session-b",
            createdAt = 1_725_000_100_000,
        )
        lateinit var updateVectors: (List<MemoryVectorRecord>) -> Unit

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                var state by remember {
                    mutableStateOf(
                        releaseState().copy(
                            currentTab = PortalTab.MEMORY,
                            memoryVectors = listOf(originalMemory),
                        ),
                    )
                }
                updateVectors = { vectors -> state = state.copy(memoryVectors = vectors) }
                RagHomeScreenContent(
                    state = state,
                    actions = RagHomeScreenActions(onDeleteMemory = deletedId::set),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag("memory-delete-memory-a").performClick()
        rule.onNodeWithTag("memory-delete-dialog").assertIsDisplayed()
        rule.runOnIdle { updateVectors(listOf(refreshedMemory)) }
        rule.onNodeWithTag("memory-delete-dialog").assertIsDisplayed()
        rule.onNodeWithTag("memory-delete-confirm").performClick()
        rule.runOnIdle { assertThat(deletedId.get()).isEqualTo("memory-a") }
    }

    @Test
    fun memoryEmptyStateIsDistinctFromContent() {
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(currentTab = PortalTab.MEMORY),
                    actions = RagHomeScreenActions(),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag("memory-empty").assertIsDisplayed()
        rule.onNodeWithTag("memory-item-memory-a").assertDoesNotExist()
    }

    @Test
    fun largeFontKeepsGraphAndConfigActionsVisible() {
        rule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(systemDensity.density, fontScale = 2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RagHomeScreenContent(
                        state = releaseState().copy(currentTab = PortalTab.MEMORY),
                        actions = RagHomeScreenActions(),
                        documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_TAB_GRAPH).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RAG_HOME_CONFIG).assertIsDisplayed()
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
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG).assertExists()

        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_BUTTON).performClick()
        rule.runOnIdle {
            assertThat(deleteCalls.get()).isEqualTo(2)
            assertThat(selections).isEmpty()
        }
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_CONFIRM_DIALOG).assertDoesNotExist()
    }

    @Test
    fun nonBlankSearchClearsInvisibleSelectionAndRemovesBatchActions() {
        val selections = mutableStateListOf("doc-a")
        val searched = AtomicReference<String?>(null)
        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                RagHomeScreenContent(
                    state = releaseState().copy(selectedIds = selections),
                    actions = RagHomeScreenActions(onSearch = searched::set),
                    documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_SELECTION_BAR).assertExists()
        rule.onNodeWithTag(UiTags.RAG_HOME_SEARCH).performTextInput("needle")

        rule.runOnIdle {
            assertThat(selections).isEmpty()
            assertThat(searched.get()).isEqualTo("needle")
        }
        rule.onNodeWithTag(UiTags.RAG_HOME_SELECTION_BAR).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RAG_HOME_DELETE_SELECTION).assertDoesNotExist()
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
    fun selectionMoveSheetAtLargeFontScrollsClicksLastOfTwentyFolders() {
        val selections = mutableStateListOf("doc-a")
        val folders = (1..20).map { index ->
            Folder(id = "folder-$index", name = "Folder $index")
        }
        val movedTarget = AtomicReference<String?>(null)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RagHomeScreenContent(
                        state = releaseState().copy(selectedIds = selections, folders = folders),
                        actions = RagHomeScreenActions(
                            onMoveDocuments = { ids, target, complete ->
                                movedTarget.set(target)
                                complete(false, ids.toList())
                            },
                        ),
                        documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_MOVE_SELECTION).performClick()
        rule.onNodeWithTag("rag-home-move-folder-list").performScrollToIndex(20)
        rule.onNodeWithText("Folder 20").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(movedTarget.get()).isEqualTo("folder-20") }
    }

    @Test
    fun selectionMoveSheetInLandscapeScrollsClicksLastOfTwentyFolders() {
        val selections = mutableStateListOf("doc-a")
        val folders = (1..20).map { index ->
            Folder(id = "folder-$index", name = "Folder $index")
        }
        val movedTarget = AtomicReference<String?>(null)
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                NexaraTheme(dynamicColor = false) {
                    RagHomeScreenContent(
                        state = releaseState().copy(selectedIds = selections, folders = folders),
                        actions = RagHomeScreenActions(
                            onMoveDocuments = { ids, target, complete ->
                                movedTarget.set(target)
                                complete(false, ids.toList())
                            },
                        ),
                        documentsContent = { modifier, _, _ -> Box(modifier.fillMaxSize()) },
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.RAG_HOME_MOVE_SELECTION).performClick()
        rule.onNodeWithTag("rag-home-move-folder-list").performScrollToIndex(20)
        rule.onNodeWithText("Folder 20").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(movedTarget.get()).isEqualTo("folder-20") }
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

    private fun ragFolderState(
        selectedIds: MutableList<String>,
        folders: List<Folder> = emptyList(),
    ) = RagFolderScreenState(
        title = "Release Documents",
        workspaceRootUuid = "root",
        documents = listOf(ragFolderDocument("doc-a"), ragFolderDocument("doc-b")),
        folders = folders,
        currentFolderId = "current-folder",
        selectedIds = selectedIds,
        contentState = RagFolderContentState.Content,
    )

    private fun ragFolderDocument(id: String) = FileEntry(
        uuid = id,
        workspaceRootUuid = "root",
        parentUuid = "current-folder",
        name = "$id release checklist with responsive typography.md",
        hash = "sha256:$id",
        mimeType = "text/markdown",
        sizeBytes = 4096,
        physicalRootPath = "/preview",
        materializedPath = "/$id.md",
        vectorizedAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun assertSingleIndexingSemantics(
        scopeTag: String,
        status: String,
        percentage: String,
        liveRegionMode: LiveRegionMode,
    ) {
        val scope = hasTestTag(scopeTag) or hasAnyAncestor(hasTestTag(scopeTag))
        rule.onAllNodes(
            scope and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(
            scope and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(
            scope and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, liveRegionMode),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(scope and hasText(status), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(scope and hasText(percentage), useUnmergedTree = true).assertCountEquals(0)
    }
}
