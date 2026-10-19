package com.promenar.nexara.ui.chat.diff

import com.promenar.nexara.data.model.FileChangeHunk
import com.promenar.nexara.data.model.FileChangeSummary
import com.promenar.nexara.data.model.FileChangeType
import com.promenar.nexara.ui.common.diff.DiffHunkInput
import com.promenar.nexara.ui.common.diff.DiffHunkLine
import com.promenar.nexara.ui.common.diff.DiffLineKind
import com.promenar.nexara.ui.common.diff.FileChangeKind
import com.promenar.nexara.ui.common.diff.FileDiffModel
import com.promenar.nexara.ui.common.diff.FileDiffStats
import com.promenar.nexara.ui.common.diff.fromHunks

/** 头部汇总只显示 `+` 或 `−` 的规则与 [FileDiffModel] 一致：新建不显示删除，删除不显示新增；移动不改内容，不显示计数。 */
internal val FileChangeSummary.shownAdded: Int?
    get() = added?.takeIf { !binary && change != FileChangeType.DELETED && change != FileChangeType.MOVED }

internal val FileChangeSummary.shownRemoved: Int?
    get() = removed?.takeIf { !binary && change != FileChangeType.CREATED && change != FileChangeType.MOVED }

/** `+N −M` 文本；没有可显示的计数时为空。 */
internal fun countsLabel(added: Int?, removed: Int?): String? =
    listOfNotNull(added?.let { "+$it" }, removed?.let { "−$it" })
        .takeIf { it.isNotEmpty() }?.joinToString(" ")

internal val FileChangeSummary.countsLabel: String?
    get() = countsLabel(shownAdded, shownRemoved)

/**
 * 多个文件的合计：只合计有计数的文件；全部文件都没有计数时为空。
 * 新建文件不计入删除、删除文件不计入新增，与单文件显示一致。
 */
internal fun List<FileChangeSummary>.totalCounts(): Pair<Int?, Int?> {
    val added = mapNotNull { it.shownAdded }
    val removed = mapNotNull { it.shownRemoved }
    return added.takeIf { it.isNotEmpty() }?.sum() to removed.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * 由已存 hunk 构建差异视图模型；没有 hunk 或数据损坏时返回空，由调用方显示计数与原因。
 * 行号取自 hunk 的起始行号，hunk 之间的未变更行显示为不可展开的折叠区段。
 */
internal fun FileChangeSummary.toDiffModel(): FileDiffModel? {
    val stored = hunks?.takeIf { it.isNotEmpty() } ?: return null
    if (binary) return null
    return runCatching {
        FileDiffModel.fromHunks(
            hunks = stored.map(::toHunkInput),
            changeKind = when (change) {
                FileChangeType.CREATED -> FileChangeKind.CREATED
                FileChangeType.DELETED -> FileChangeKind.DELETED
                FileChangeType.MODIFIED, FileChangeType.MOVED -> FileChangeKind.MODIFIED
            },
            fileName = null,
            stats = if (added != null && removed != null) FileDiffStats(added, removed, approximate) else null,
        )
    }.getOrNull()
}

private fun toHunkInput(hunk: FileChangeHunk): DiffHunkInput {
    val lines = ArrayList<DiffHunkLine>(hunk.lines.size)
    hunk.lines.forEach { raw ->
        if (raw == FileChangeHunk.NO_NEWLINE_MARK) {
            require(lines.isNotEmpty()) { "缺少末尾换行标记前没有行" }
            lines[lines.lastIndex] = lines.last().copy(missingFinalNewline = true)
            return@forEach
        }
        require(raw.isNotEmpty()) { "差异行缺少标记" }
        val kind = when (raw[0]) {
            FileChangeHunk.CONTEXT_MARK -> DiffLineKind.CONTEXT
            FileChangeHunk.ADDED_MARK -> DiffLineKind.ADDED
            FileChangeHunk.REMOVED_MARK -> DiffLineKind.REMOVED
            else -> throw IllegalArgumentException("未知差异行标记")
        }
        lines += DiffHunkLine(kind, raw.substring(1))
    }
    return DiffHunkInput(hunk.oldStart, hunk.newStart, lines)
}
