package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class RagHomeScreenContractTest {
    private val screenSource by lazy {
        String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt"),
            ),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `route owns runtime dependencies while content remains deterministic`() {
        assertThat(screenSource).contains("data class RagHomeScreenState")
        assertThat(screenSource).contains("data class RagHomeScreenActions")
        assertThat(screenSource).contains("internal fun RagHomeScreenContent")

        val contentStart = screenSource.indexOf("internal fun RagHomeScreenContent")
        assertThat(contentStart).isAtLeast(0)
        val contentBody = screenSource.substring(contentStart)
        assertThat(contentBody).doesNotContain("RagViewModel")
        assertThat(contentBody).doesNotContain("LocalContext")
        assertThat(contentBody).doesNotContain("rememberLauncherForActivityResult")
        assertThat(contentBody).doesNotContain("getWorkspaceRepo")

        val routeBody = screenSource.substring(0, contentStart)
        assertThat(routeBody).contains("RagHomeScreenContent(")
        assertThat(routeBody).contains("FilesPanel(")
        assertThat(routeBody).contains("showSelectionOverlay = false")
        assertThat(routeBody).contains("onDelete = requestDelete")
        assertThat(routeBody).doesNotContain("actions.onDeleteDocuments(ids)")
        assertThat(routeBody).contains("onOpenConfig = onNavigateToConfig")
        assertThat(routeBody).contains("onOpenGraph = onNavigateToGraph")
    }

    @Test
    fun `content exposes stable anchors and real navigation actions`() {
        assertThat(screenSource).contains("UiTags.RAG_HOME_ROOT")
        assertThat(screenSource).contains("UiTags.RAG_HOME_SEARCH")
        assertThat(screenSource).contains("UiTags.RAG_HOME_TAB_DOCUMENTS")
        assertThat(screenSource).contains("UiTags.RAG_HOME_TAB_MEMORY")
        assertThat(screenSource).contains("UiTags.RAG_HOME_TAB_GRAPH")
        assertThat(screenSource).contains("UiTags.RAG_HOME_CONFIG")
        assertThat(screenSource).contains("UiTags.RAG_HOME_NEW_FOLDER")
        assertThat(screenSource).contains("UiTags.RAG_HOME_UPLOAD")
        assertThat(screenSource).contains("UiTags.RAG_HOME_DOCUMENTS_CONTENT")
        assertThat(screenSource).contains("UiTags.RAG_HOME_MEMORY_CONTENT")
        assertThat(screenSource).contains("actions.onOpenConfig")
        assertThat(screenSource).contains("actions.onOpenGraph")
    }

    @Test
    fun `ui tag registry contains the rag home release contract`() {
        val tags = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt"),
            ),
            Charsets.UTF_8,
        )

        listOf(
            "RAG_HOME_ROOT",
            "RAG_HOME_SEARCH",
            "RAG_HOME_TAB_DOCUMENTS",
            "RAG_HOME_TAB_MEMORY",
            "RAG_HOME_TAB_GRAPH",
            "RAG_HOME_CONFIG",
            "RAG_HOME_NEW_FOLDER",
            "RAG_HOME_UPLOAD",
            "RAG_HOME_DOCUMENTS_CONTENT",
            "RAG_HOME_MEMORY_CONTENT",
            "RAG_HOME_INDEXING_NOTICE",
            "RAG_HOME_NEW_FOLDER_DIALOG",
            "RAG_HOME_MOVE_SHEET",
            "RAG_HOME_SELECTION_BAR",
            "RAG_HOME_CLEAR_SELECTION",
            "RAG_HOME_MOVE_SELECTION",
            "RAG_HOME_REINDEX_SELECTION",
            "RAG_HOME_DELETE_SELECTION",
            "RAG_HOME_DELETE_CONFIRM_DIALOG",
            "RAG_HOME_DELETE_CONFIRM_BUTTON",
        ).forEach { tag -> assertThat(tags).contains(tag) }
    }

    @Test
    fun `failed batch operation retains only failed selections while success clears all`() {
        val failedSelection = mutableListOf("doc-a", "doc-b", "doc-c")
        assertThat(
            applyBatchSelectionResult(
                selectedIds = failedSelection,
                movingIds = setOf("doc-a", "doc-b", "doc-c"),
                failedIds = setOf("doc-b"),
                succeeded = false,
            ),
        ).isFalse()
        assertThat(failedSelection).containsExactly("doc-b")

        val unknownFailureSelection = mutableListOf("doc-a", "doc-b")
        assertThat(
            applyBatchSelectionResult(
                selectedIds = unknownFailureSelection,
                movingIds = setOf("doc-a", "doc-b"),
                failedIds = emptySet(),
                succeeded = false,
            ),
        ).isFalse()
        assertThat(unknownFailureSelection).containsExactly("doc-a", "doc-b")

        val successfulSelection = mutableListOf("doc-a", "doc-b")
        assertThat(
            applyBatchSelectionResult(
                selectedIds = successfulSelection,
                movingIds = setOf("doc-a", "doc-b"),
                failedIds = emptySet(),
                succeeded = true,
            ),
        ).isTrue()
        assertThat(successfulSelection).isEmpty()
    }

    @Test
    fun `files panel keeps its overlay by default while rag disables it explicitly`() {
        val filesPanelSource = String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/chat/components/FilesPanel.kt"),
            ),
            Charsets.UTF_8,
        )

        assertThat(filesPanelSource).contains("showSelectionOverlay: Boolean = true")
        assertThat(filesPanelSource).contains("showSelectionOverlay && isMultiSelectMode")
        assertThat(screenSource).contains("showSelectionOverlay = false")
    }
}
