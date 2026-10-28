package com.promenar.infinity.ui.common.diff

import androidx.compose.runtime.Immutable

/** 差异区段：[Shown] 始终显示；[Folded] 是默认折叠的连续未变更行，展开后按原样显示。 */
@Immutable
sealed interface DiffSegment {
    val lines: List<NumberedDiffLine>

    data class Shown(override val lines: List<NumberedDiffLine>) : DiffSegment

    data class Folded(override val lines: List<NumberedDiffLine>) : DiffSegment
}

/**
 * 折叠未变更区段：每处变更前后各保留 [contextLines] 行上下文，其余连续未变更行收为一个 [DiffSegment.Folded]。
 * 与 `MyersDiff.computeHunks` 的分组规则一致：两处变更之间的未变更行不超过 `2 × contextLines` 时不折叠。
 * 没有任何变更时整段折叠。拼接所有区段的行即得到原输入。
 */
fun foldUnchangedSegments(lines: List<NumberedDiffLine>, contextLines: Int = 3): List<DiffSegment> {
    require(contextLines >= 0) { "contextLines 不能为负数" }
    if (lines.isEmpty()) return emptyList()
    val result = mutableListOf<DiffSegment>()
    val pending = mutableListOf<NumberedDiffLine>()

    fun flushShown() {
        if (pending.isNotEmpty()) {
            result += DiffSegment.Shown(pending.toList())
            pending.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        if (lines[index].kind != DiffLineKind.CONTEXT) {
            pending += lines[index]
            index++
            continue
        }
        val runStart = index
        while (index < lines.size && lines[index].kind == DiffLineKind.CONTEXT) index++
        val runEnd = index
        val keepBefore = if (runStart > 0) contextLines else 0
        val keepAfter = if (runEnd < lines.size) contextLines else 0
        if (runEnd - runStart <= keepBefore + keepAfter) {
            pending += lines.subList(runStart, runEnd)
            continue
        }
        pending += lines.subList(runStart, runStart + keepBefore)
        flushShown()
        result += DiffSegment.Folded(lines.subList(runStart + keepBefore, runEnd - keepAfter).toList())
        pending += lines.subList(runEnd - keepAfter, runEnd)
    }
    flushShown()
    return result
}

/** 并排视图的一行：左侧为旧文件行，右侧为新文件行；缺失一侧为空。 */
@Immutable
data class SideBySideRow(
    val old: NumberedDiffLine?,
    val new: NumberedDiffLine?,
)

/**
 * 并排配对：未变更行左右对齐；一段连续变更内的删除行与新增行按出现顺序逐一配对，
 * 多出的一侧对面留空。变更段不会跨越未变更行，因此可对单个区段独立配对。
 */
fun pairSideBySide(lines: List<NumberedDiffLine>): List<SideBySideRow> {
    val rows = ArrayList<SideBySideRow>(lines.size)
    val removed = ArrayList<NumberedDiffLine>()
    val added = ArrayList<NumberedDiffLine>()

    fun flushChanges() {
        for (index in 0 until maxOf(removed.size, added.size)) {
            rows += SideBySideRow(removed.getOrNull(index), added.getOrNull(index))
        }
        removed.clear()
        added.clear()
    }

    lines.forEach { line ->
        when (line.kind) {
            DiffLineKind.CONTEXT -> {
                flushChanges()
                rows += SideBySideRow(line, line)
            }
            DiffLineKind.REMOVED -> removed += line
            DiffLineKind.ADDED -> added += line
        }
    }
    flushChanges()
    return rows
}

/** 单行显示上限：超长行（如压缩后的脚本）截断显示，避免单个文本节点宽度超出布局约束可表示的范围。 */
const val DIFF_MAX_DISPLAY_CHARS = 1_000

/** 显示用文本：制表符展开为 4 个空格，超过 [maxChars] 时截断并追加省略号；不改变代理对完整性。 */
fun diffDisplayText(content: String, maxChars: Int = DIFF_MAX_DISPLAY_CHARS): String {
    val expanded = if (content.indexOf('\t') >= 0) content.replace("\t", "    ") else content
    if (expanded.length <= maxChars) return expanded
    var end = maxChars
    if (Character.isHighSurrogate(expanded[end - 1])) end--
    return expanded.substring(0, end) + "…"
}

/**
 * 估算单行显示宽度（以半角字符为单位），用于挑选最长行候选再精确测量：
 * 码点不低于 U+1100（韩文、中日文、全角符号与表情等）按 2 计，其余按 1 计。
 */
fun estimatedDisplayColumns(text: String): Int {
    var columns = 0
    text.forEach { char -> columns += if (char.code >= 0x1100) 2 else 1 }
    return columns
}

/** 按 [estimatedDisplayColumns] 返回最宽的至多 [count] 行显示文本，供精确测量。 */
fun widestDisplayCandidates(lines: Iterable<NumberedDiffLine>, count: Int = 3): List<String> {
    val top = ArrayList<Pair<Int, String>>(count + 1)
    lines.forEach { line ->
        val text = diffDisplayText(line.content)
        val columns = estimatedDisplayColumns(text)
        if (top.size < count || columns > top.last().first) {
            top += columns to text
            top.sortByDescending { it.first }
            if (top.size > count) top.removeAt(top.lastIndex)
        }
    }
    return top.map { it.second }
}
