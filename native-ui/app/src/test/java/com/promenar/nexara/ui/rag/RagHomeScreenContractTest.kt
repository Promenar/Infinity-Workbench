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
    fun `pending且没有notice时Home与Folder共用重试提示资源`() {
        assertThat(ragIndexFallbackStatusResource(canRetryPendingIndex = true))
            .isEqualTo(com.promenar.nexara.R.string.rag_index_retry_hint)
        assertThat(ragIndexFallbackStatusResource(canRetryPendingIndex = false))
            .isEqualTo(com.promenar.nexara.R.string.rag_index_phase_unknown)
    }

    @Test
    fun `pending独立进入Home与Folder索引区外层gate`() {
        assertThat(
            shouldShowRagIndexSection(
                isIndexing = false,
                hasNotice = false,
                canRetryPendingIndex = true,
            ),
        ).isTrue()
        assertThat(shouldShowRagIndexSection(false, false, false)).isFalse()
        assertThat(shouldShowRagIndexSection(true, false, false)).isTrue()
        assertThat(shouldShowRagIndexSection(false, true, false)).isTrue()
    }

    @Test
    fun `pending且没有notice时Home与Folder仍渲染可达操作区`() {
        assertThat(
            shouldShowRagIndexActions(
                hasNotice = false,
                canRetryPendingIndex = true,
            ),
        ).isTrue()
        assertThat(
            shouldShowRagIndexActions(
                hasNotice = false,
                canRetryPendingIndex = false,
            ),
        ).isFalse()
        assertThat(
            shouldShowRagIndexActions(
                hasNotice = true,
                canRetryPendingIndex = false,
            ),
        ).isTrue()
    }

    @Test
    fun `Home动作优先服从可见notice且仅无notice时使用pending`() {
        assertThat(
            resolveRagHomeIndexAction(
                noticeCode = IndexingNotice.CODE_FAILED,
                canRetryLastFailedIndex = true,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagHomeIndexAction.RetryFailed)
        assertThat(
            resolveRagHomeIndexAction(
                noticeCode = IndexingNotice.CODE_IMPORT_FAILED,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagHomeIndexAction.None)
        assertThat(
            resolveRagHomeIndexAction(
                noticeCode = null,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagHomeIndexAction.RetryPending)
        assertThat(
            resolveRagHomeIndexAction(null, false, false),
        ).isEqualTo(RagHomeIndexAction.None)
    }

    @Test
    fun `Folder动作优先服从import move delete与失败notice`() {
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_IMPORT_FAILED,
                hasSelection = false,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.Upload)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_MOVE_FAILED,
                hasSelection = true,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.Move)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_DELETE_FAILED,
                hasSelection = true,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.Delete)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_PARTIAL,
                hasSelection = true,
                canRetryLastFailedIndex = true,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.RetryFailed)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_WARNING,
                hasSelection = true,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.None)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = IndexingNotice.CODE_FAILED,
                hasSelection = true,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.None)
        assertThat(
            resolveRagFolderIndexAction(
                noticeCode = null,
                hasSelection = false,
                canRetryLastFailedIndex = false,
                canRetryPendingIndex = true,
            ),
        ).isEqualTo(RagFolderIndexAction.RetryPending)
        assertThat(
            resolveRagFolderIndexAction(null, false, false, false),
        ).isEqualTo(RagFolderIndexAction.None)
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
    fun `legacy graph tab long press deletion and eager folder rows stay disabled`() {
        assertThat(screenSource).doesNotContain("PortalTab.GRAPH to")
        assertThat(screenSource).doesNotContain("onLongClick")
        assertThat(screenSource).doesNotContain("state.folders.forEach")
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

    @Test
    fun `move sheet does not use a fixed seventy percent height`() {
        val fixedHeightRatio = Regex("""\.fillMaxHeight\s*\(\s*0\.7f\s*\)""")

        assertThat(fixedHeightRatio.containsMatchIn(screenSource)).isFalse()
    }
}
