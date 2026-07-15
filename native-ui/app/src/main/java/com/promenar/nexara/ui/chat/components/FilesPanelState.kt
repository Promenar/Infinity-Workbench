package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.data.local.db.entity.FileEntry

internal enum class FilesPanelUiState {
    Loading,
    Error,
    Empty,
    SearchEmpty,
    Content,
}

internal fun resolveFilesPanelUiState(
    workspaceReady: Boolean,
    isLoading: Boolean,
    hasLoadError: Boolean,
    itemCount: Int,
    filteredItemCount: Int,
    searchQuery: String,
): FilesPanelUiState = when {
    isLoading -> FilesPanelUiState.Loading
    hasLoadError -> FilesPanelUiState.Error
    !workspaceReady -> FilesPanelUiState.Loading
    filteredItemCount > 0 -> FilesPanelUiState.Content
    searchQuery.isNotBlank() && itemCount > 0 -> FilesPanelUiState.SearchEmpty
    else -> FilesPanelUiState.Empty
}

/** 文件批量操作的稳定结果；底层异常不得进入 UI 文案。 */
data class FileBatchOperationResult(
    val attemptedIds: List<String>,
    val failedIds: List<String> = emptyList(),
) {
    val succeededIds: Set<String> = attemptedIds.toSet() - failedIds.toSet()
    val allSucceeded: Boolean = attemptedIds.isNotEmpty() && failedIds.isEmpty()
}

/** 成功项退出选择，失败项继续保留，原本未参与操作的选择不受影响。 */
internal fun reduceSelectionAfterFileOperation(
    selectedIds: MutableList<String>,
    result: FileBatchOperationResult,
) {
    selectedIds.removeAll(result.succeededIds)
    result.failedIds.forEach { failedId ->
        if (failedId !in selectedIds) selectedIds.add(failedId)
    }
}

/**
 * 从搜索命中项向上追溯到工作区根，得到需要渲染的精确祖先链。
 * lookup 是 suspend 契约，便于直接复用仓库且保持取消传播。
 */
internal suspend fun resolveSearchVisibleIds(
    matches: List<FileEntry>,
    workspaceRootUuid: String,
    lookup: suspend (String) -> FileEntry?,
): Set<String> = buildSet {
    matches.forEach { match ->
        var current: FileEntry? = match
        val visited = mutableSetOf<String>()
        while (current != null && current.uuid != workspaceRootUuid && visited.add(current.uuid)) {
            add(current.uuid)
            val parentUuid = current.parentUuid
            current = if (parentUuid.isNullOrBlank() || parentUuid == workspaceRootUuid) {
                null
            } else {
                lookup(parentUuid)
            }
        }
    }
}
