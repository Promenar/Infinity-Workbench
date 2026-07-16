package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.data.local.db.entity.FileEntry

internal enum class FilesPanelUiState {
    Loading,
    Error,
    Empty,
    SearchEmpty,
    Content,
}

/** 单层文件树中的可见节点；稳定身份始终来自 [file] 的 UUID。 */
internal data class VisibleFileNode(
    val file: FileEntry,
    val depth: Int,
)

/**
 * 把树投影为单层、稳定顺序的可见节点列表。
 *
 * 默认展开前两层目录；用户覆盖和搜索强制展开都按 UUID 绑定，不受重排影响。
 * visited 同时防止异常仓库数据中的环或重复 UUID 让 UI 无限递归。
 */
internal fun projectVisibleFileNodes(
    roots: List<FileEntry>,
    childrenByParent: Map<String, List<FileEntry>>,
    expansionOverrides: Map<String, Boolean> = emptyMap(),
    forceExpandedIds: Set<String> = emptySet(),
): List<VisibleFileNode> = buildList {
    val visited = mutableSetOf<String>()

    fun append(entries: List<FileEntry>, depth: Int) {
        entries.forEach { file ->
            if (!visited.add(file.uuid)) return@forEach
            add(VisibleFileNode(file = file, depth = depth))
            if (!file.isDirectory) return@forEach

            val expanded = isFileNodeExpanded(
                uuid = file.uuid,
                depth = depth,
                expansionOverrides = expansionOverrides,
                forceExpandedIds = forceExpandedIds,
            )
            if (expanded) append(childrenByParent[file.uuid].orEmpty(), depth + 1)
        }
    }

    append(roots, depth = 0)
}

internal fun isFileNodeExpanded(
    uuid: String,
    depth: Int,
    expansionOverrides: Map<String, Boolean>,
    forceExpandedIds: Set<String> = emptySet(),
): Boolean = uuid in forceExpandedIds || (expansionOverrides[uuid] ?: (depth < DEFAULT_EXPANDED_DEPTH))

internal fun boundedFileTreeIndentLevel(depth: Int): Int =
    depth.coerceIn(0, MAX_FILE_TREE_INDENT_LEVEL)

private const val DEFAULT_EXPANDED_DEPTH = 2
private const val MAX_FILE_TREE_INDENT_LEVEL = 4

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
