package com.promenar.nexara.ui.common.diff

import androidx.compose.runtime.Immutable

/**
 * 内容未保存的未变更区段：只知道起始行号与行数，渲染为不可展开的折叠行。
 * 由 [fromHunks] 在相邻 hunk 之间与首个 hunk 之前生成；[lineCount] 为空表示行数未知。
 * 不含任何行，因此“拼接所有区段的行即得到 [FileDiffContent.Lines.lines]”仍然成立。
 */
@Immutable
data class OmittedDiffSegment(
    val oldStart: Int,
    val newStart: Int,
    val lineCount: Int?,
) : DiffSegment {
    override val lines: List<NumberedDiffLine> get() = emptyList()
}

/** 已存差异中的一行；[missingFinalNewline] 表示该行所在一侧缺少末尾换行。 */
@Immutable
data class DiffHunkLine(
    val kind: DiffLineKind,
    val content: String,
    val missingFinalNewline: Boolean = false,
)

/**
 * 已存的一段差异（统一差异格式的 hunk）。[oldStart] / [newStart] 为本段第一行在旧 / 新文件中的行号（1 起）；
 * 本段在某一侧没有行时，表示该侧此位置之前的行数加一（与 `MyersDiff.computeHunks` 一致）。
 */
@Immutable
data class DiffHunkInput(
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffHunkLine>,
)

/**
 * 由只含局部片段的 hunk 构建模型：保留真实行号，每个 hunk 成为一个 [DiffSegment.Shown]，
 * 首个 hunk 之前与相邻 hunk 之间的未变更行成为 [OmittedDiffSegment]（内容未保存，不可展开）。
 * 末个 hunk 之后的行数未知，不生成区段。
 *
 * hunk 必须按行号升序且互不重叠，否则抛出 [IllegalArgumentException]。
 * [stats] 为空时按 hunk 中的增删行统计；调用方持有更完整的计数（例如工具执行时的统计）时可直接传入。
 */
fun FileDiffModel.Companion.fromHunks(
    hunks: List<DiffHunkInput>,
    changeKind: FileChangeKind,
    fileName: String? = null,
    stats: FileDiffStats? = null,
): FileDiffModel {
    val numbered = ArrayList<NumberedDiffLine>()
    val segments = ArrayList<DiffSegment>()
    var nextOld = 1
    var nextNew = 1
    hunks.forEach { hunk ->
        require(hunk.oldStart >= 1 && hunk.newStart >= 1) { "hunk 行号必须从 1 起" }
        val oldGap = hunk.oldStart - nextOld
        val newGap = hunk.newStart - nextNew
        require(oldGap >= 0 && newGap >= 0) { "hunk 必须按行号升序且互不重叠" }
        if (oldGap > 0 || newGap > 0) {
            segments += OmittedDiffSegment(nextOld, nextNew, oldGap.takeIf { it == newGap })
        }
        var oldNumber = hunk.oldStart
        var newNumber = hunk.newStart
        val hunkLines = hunk.lines.map { line ->
            when (line.kind) {
                DiffLineKind.CONTEXT -> NumberedDiffLine(line.kind, oldNumber++, newNumber++, line.content, line.missingFinalNewline)
                DiffLineKind.REMOVED -> NumberedDiffLine(line.kind, oldNumber++, null, line.content, line.missingFinalNewline)
                DiffLineKind.ADDED -> NumberedDiffLine(line.kind, null, newNumber++, line.content, line.missingFinalNewline)
            }
        }
        if (hunkLines.isNotEmpty()) {
            numbered += hunkLines
            segments += DiffSegment.Shown(hunkLines)
        }
        nextOld = oldNumber
        nextNew = newNumber
    }
    return FileDiffModel(
        fileName = fileName,
        changeKind = changeKind,
        stats = stats ?: statsOf(numbered),
        content = FileDiffContent.Lines(numbered, segments),
    )
}
