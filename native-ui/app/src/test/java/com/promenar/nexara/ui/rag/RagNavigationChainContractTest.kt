package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class RagNavigationChainContractTest {
    private fun source(path: String): String = String(
        Files.readAllBytes(Path.of(path)),
        Charsets.UTF_8,
    )

    @Test
    fun `files panel exposes a compatible folder activation callback and propagates it`() {
        val filesPanel = source(
            "app/src/main/java/com/promenar/nexara/ui/chat/components/FilesPanel.kt",
        )

        assertThat(filesPanel).contains("onFolderClick: ((String, String) -> Unit)? = null")
        assertThat(filesPanel).contains("onFolderClick?.invoke(file.uuid, file.name)")
        assertThat(filesPanel).contains("onFolderClick = onFolderClick")
    }

    @Test
    fun `rag home sends folder activation into navigation without changing file navigation`() {
        val ragHome = source(
            "app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt",
        )

        assertThat(ragHome).contains("onFolderClick = onNavigateToFolder")
        assertThat(ragHome).contains("actions.onNavigateToDocEditor(root, docId)")
    }

    @Test
    fun `rag folder document body opens editor while checkbox remains the selection control`() {
        val ragFolder = source(
            "app/src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt",
        )

        assertThat(ragFolder).contains("onNavigateToDocEditor: (String, String) -> Unit")
        assertThat(ragFolder).contains("onNavigateToDocEditor(rootUuid, doc.uuid)")
        assertThat(ragFolder).doesNotContain("if (isSelected) selectedIds.remove(doc.uuid)")
        assertThat(ragFolder).contains("getByUuid(rootUuid, folderId)")
        assertThat(ragFolder).contains("resolvedFolderName ?: stringResource(R.string.rag_home_documents)")
    }

    @Test
    fun `navigation graph connects rag folder documents to doc editor`() {
        val navGraph = source(
            "app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt",
        )

        assertThat(navGraph).contains("onNavigateToDocEditor = { rootUuid, docId ->")
        assertThat(navGraph).contains("NavDestinations.docEditor(rootUuid, docId)")
        assertThat(navGraph).contains("const val RAG_FOLDER = \"rag_folder/{folderId}\"")
        assertThat(navGraph).doesNotContain("navArgument(\"folderName\")")
    }
}
