package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FilesPanelStateTest {

    @Test
    fun `首次加载时不显示空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = false,
            isLoading = true,
            hasLoadError = false,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Loading)
    }

    @Test
    fun `加载完成且没有文件时显示真实空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Empty)
    }

    @Test
    fun `有文件但搜索不匹配时显示搜索空态`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 2,
            filteredItemCount = 0,
            searchQuery = "missing",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.SearchEmpty)
    }

    @Test
    fun `加载失败优先显示错误而非空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = false,
            isLoading = false,
            hasLoadError = true,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Error)
    }

    @Test
    fun `过滤后仍有文件时显示内容`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 2,
            filteredItemCount = 1,
            searchQuery = "doc",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Content)
    }

    @Test
    fun `部分删除后只保留失败项的选择`() {
        val selected = mutableListOf("doc-1", "doc-2", "doc-3")

        reduceSelectionAfterFileOperation(
            selectedIds = selected,
            result = FileBatchOperationResult(
                attemptedIds = listOf("doc-1", "doc-2"),
                failedIds = listOf("doc-2"),
            ),
        )

        assertThat(selected).containsExactly("doc-2", "doc-3").inOrder()
    }

    @Test
    fun `嵌套搜索结果包含所有祖先但不包含无关分支`() = runTest {
        val rootFolder = entry("folder", "root", "/folder", isDirectory = true)
        val nestedFolder = entry("nested", "folder", "/folder/nested", isDirectory = true)
        val match = entry("match", "nested", "/folder/nested/needle.txt", isDirectory = false)
        val unrelated = entry("other", "root", "/other", isDirectory = true)
        val entries = listOf(rootFolder, nestedFolder, match, unrelated).associateBy { it.uuid }

        val visibleIds = resolveSearchVisibleIds(
            matches = listOf(match),
            workspaceRootUuid = "root",
            lookup = { entries[it] },
        )

        assertThat(visibleIds).containsExactly("match", "nested", "folder")
        assertThat(visibleIds).doesNotContain("other")
    }

    private fun entry(
        uuid: String,
        parentUuid: String,
        path: String,
        isDirectory: Boolean,
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parentUuid,
        name = path.substringAfterLast('/'),
        hash = "",
        isDirectory = isDirectory,
        physicalRootPath = "/tmp$path",
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
