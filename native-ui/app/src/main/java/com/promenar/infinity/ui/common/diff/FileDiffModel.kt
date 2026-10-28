package com.promenar.infinity.ui.common.diff

import androidx.compose.runtime.Immutable
import com.promenar.infinity.infra.util.MyersDiff

/** 差异行类别；与 [MyersDiff.DiffLine.type] 的 `context` / `added` / `removed` 一一对应。 */
enum class DiffLineKind {
    CONTEXT,
    ADDED,
    REMOVED,
    ;

    companion object {
        fun fromMyersType(type: String): DiffLineKind = when (type) {
            "context" -> CONTEXT
            "added" -> ADDED
            "removed" -> REMOVED
            else -> throw IllegalArgumentException("未知差异行类型：$type")
        }
    }
}

/**
 * 带行号的差异行。删除行只有旧行号，新增行只有新行号，未变更行两者都有。
 * [missingFinalNewline] 仅在新旧两侧末尾换行状态不同时，标在缺少末尾换行一侧的最后一行上。
 */
@Immutable
data class NumberedDiffLine(
    val kind: DiffLineKind,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String,
    val missingFinalNewline: Boolean = false,
)

/** 文件级变更类型：决定头部汇总只显示 `+`、只显示 `−` 还是两者都显示。 */
enum class FileChangeKind { CREATED, DELETED, MODIFIED }

/** 新增与删除行数；[isApproximate] 为真时按行多重集合估算（移动的行不计入），仅出现在摘要降级中。 */
@Immutable
data class FileDiffStats(
    val added: Int,
    val removed: Int,
    val isApproximate: Boolean = false,
)

/** 摘要降级原因：超出大小阈值，或差异过于复杂、逐行计算会占用过多内存。 */
enum class FileDiffSummaryReason { LARGE_FILE, TOO_COMPLEX }

/**
 * 逐行渲染与降级阈值。
 * - 任一侧超过 [maxLinesPerSide] 行或 [maxBytesPerSide] 字节（UTF-8）时只给出摘要；
 * - [maxEditTraceCells] 约束 [MyersDiff.compute] 的回溯表规模（约等于“编辑距离 × 两侧行数和 × 2”个整型），
 *   超出时降级为 [FileDiffSummaryReason.TOO_COMPLEX]，避免整文件重写时一次分配上百 MB；
 * - [maxSummaryEditOps] 约束摘要计数的计算量，超出时改用多重集合估算；
 * - [contextLines] 为变更前后保留的未变更行数。
 */
@Immutable
data class FileDiffLimits(
    val maxLinesPerSide: Int = 5_000,
    val maxBytesPerSide: Long = 512L * 1024L,
    val maxEditTraceCells: Long = 4_000_000L,
    val maxSummaryEditOps: Long = 16_000_000L,
    val contextLines: Int = 3,
) {
    init {
        require(maxLinesPerSide > 0) { "maxLinesPerSide 必须大于 0" }
        require(maxBytesPerSide > 0) { "maxBytesPerSide 必须大于 0" }
        require(maxEditTraceCells > 0) { "maxEditTraceCells 必须大于 0" }
        require(maxSummaryEditOps > 0) { "maxSummaryEditOps 必须大于 0" }
        require(contextLines >= 0) { "contextLines 不能为负数" }
    }

    companion object {
        val Default = FileDiffLimits()
    }
}

/** 差异视图的显示内容：二进制降级、摘要降级或逐行差异。 */
@Immutable
sealed interface FileDiffContent {
    data object Binary : FileDiffContent

    data class Summary(val reason: FileDiffSummaryReason) : FileDiffContent

    /** [segments] 是 [lines] 按 [foldUnchangedSegments] 划分后的结果，拼接后与 [lines] 完全一致。 */
    data class Lines(
        val lines: List<NumberedDiffLine>,
        val segments: List<DiffSegment>,
    ) : FileDiffContent
}

/**
 * 文件差异视图的完整数据模型，与 UI 无关，可在后台线程构建后交给 `FileDiffView` 渲染。
 * 二进制文件的 [stats] 为空。
 */
@Immutable
data class FileDiffModel(
    val fileName: String?,
    val changeKind: FileChangeKind,
    val stats: FileDiffStats?,
    val content: FileDiffContent,
) {
    /** 头部要显示的新增行数；删除文件不显示 `+`，二进制文件两者都不显示。 */
    val headerAdded: Int?
        get() = stats?.added?.takeIf { changeKind != FileChangeKind.DELETED }

    /** 头部要显示的删除行数；新建文件不显示 `−`。 */
    val headerRemoved: Int?
        get() = stats?.removed?.takeIf { changeKind != FileChangeKind.CREATED }

    /** 头部汇总文本，如 `+12 −3`、`+40`、`−7`；二进制文件为空。 */
    val countsLabel: String?
        get() = listOfNotNull(
            headerAdded?.let { "+$it" },
            headerRemoved?.let { "−$it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")

    companion object {
        /**
         * 由新旧全文构建模型。换行符 `\n`、`\r\n`、`\r` 均视为行尾；末尾换行不产生额外空行，
         * 但两侧末尾换行状态不同时，最后一行按一删一增处理（与 Git 一致）。
         * 文本含 NUL 字符时视为二进制。[changeKind] 为空时按“旧侧为空 → 新建、新侧为空 → 删除”推断。
         *
         * 计算量与输入规模相关，超大输入应在 `Dispatchers.Default` 上调用。
         */
        fun fromTexts(
            oldText: String,
            newText: String,
            fileName: String? = null,
            isBinary: Boolean = false,
            changeKind: FileChangeKind? = null,
            limits: FileDiffLimits = FileDiffLimits.Default,
        ): FileDiffModel {
            if (isBinary || oldText.indexOf('\u0000') >= 0 || newText.indexOf('\u0000') >= 0) {
                return FileDiffModel(fileName, changeKind ?: inferKind(oldText.isEmpty(), newText.isEmpty()), null, FileDiffContent.Binary)
            }
            val oldSplit = splitDiffText(oldText)
            val newSplit = splitDiffText(newText)
            val kind = changeKind ?: inferKind(oldSplit.lines.isEmpty(), newSplit.lines.isEmpty())
            val oldLines = oldSplit.taggedLines(newSplit)
            val newLines = newSplit.taggedLines(oldSplit)

            val prefix = commonPrefixLength(oldLines, newLines)
            val suffix = commonSuffixLength(oldLines, newLines, prefix)
            val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
            val newMiddle = newLines.subList(prefix, newLines.size - suffix)

            val tooLarge = oldLines.size > limits.maxLinesPerSide ||
                newLines.size > limits.maxLinesPerSide ||
                utf8Length(oldText) > limits.maxBytesPerSide ||
                utf8Length(newText) > limits.maxBytesPerSide
            if (tooLarge) {
                val stats = summaryStats(oldMiddle, newMiddle, summaryMaxD(limits, oldMiddle.size + newMiddle.size))
                return FileDiffModel(fileName, kind, stats, FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
            }

            val middleDiff: List<MyersDiff.DiffLine> = if (oldMiddle.isEmpty() || newMiddle.isEmpty()) {
                MyersDiff.compute(oldMiddle, newMiddle)
            } else {
                val cellsPerStep = 2L * (oldMiddle.size + newMiddle.size) + 1L
                val maxD = (limits.maxEditTraceCells / cellsPerStep - 1L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val distance = editDistance(oldMiddle, newMiddle, maxD)
                    ?: return FileDiffModel(
                        fileName,
                        kind,
                        summaryStats(oldMiddle, newMiddle, summaryMaxD(limits, oldMiddle.size + newMiddle.size)),
                        FileDiffContent.Summary(FileDiffSummaryReason.TOO_COMPLEX),
                    )
                if (distance == 0) emptyList() else MyersDiff.compute(oldMiddle, newMiddle)
            }
            val diff = buildList(prefix + middleDiff.size + suffix) {
                for (index in 0 until prefix) add(MyersDiff.DiffLine("context", oldLines[index]))
                addAll(middleDiff)
                for (index in oldLines.size - suffix until oldLines.size) add(MyersDiff.DiffLine("context", oldLines[index]))
            }
            val numbered = numberDiffLines(diff)
            return FileDiffModel(
                fileName = fileName,
                changeKind = kind,
                stats = statsOf(numbered),
                content = FileDiffContent.Lines(numbered, foldUnchangedSegments(numbered, limits.contextLines)),
            )
        }

        /**
         * 由已计算的完整行差异（例如 [MyersDiff.compute] 的输出，而非只含局部的 hunk）构建模型。
         * 行号从 1 起按顺序推算；已计算差异不携带末尾换行信息。
         */
        fun fromDiffLines(
            diffLines: List<MyersDiff.DiffLine>,
            fileName: String? = null,
            isBinary: Boolean = false,
            changeKind: FileChangeKind? = null,
            limits: FileDiffLimits = FileDiffLimits.Default,
        ): FileDiffModel {
            var oldCount = 0
            var newCount = 0
            var oldBytes = 0L
            var newBytes = 0L
            diffLines.forEach { line ->
                val bytes = utf8Length(line.content) + 1L
                when (DiffLineKind.fromMyersType(line.type)) {
                    DiffLineKind.CONTEXT -> {
                        oldCount++; newCount++; oldBytes += bytes; newBytes += bytes
                    }
                    DiffLineKind.REMOVED -> {
                        oldCount++; oldBytes += bytes
                    }
                    DiffLineKind.ADDED -> {
                        newCount++; newBytes += bytes
                    }
                }
            }
            val kind = changeKind ?: inferKind(oldCount == 0, newCount == 0)
            if (isBinary) return FileDiffModel(fileName, kind, null, FileDiffContent.Binary)
            val tooLarge = oldCount > limits.maxLinesPerSide || newCount > limits.maxLinesPerSide ||
                oldBytes > limits.maxBytesPerSide || newBytes > limits.maxBytesPerSide
            if (tooLarge) {
                val stats = FileDiffStats(
                    added = diffLines.count { it.type == "added" },
                    removed = diffLines.count { it.type == "removed" },
                )
                return FileDiffModel(fileName, kind, stats, FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
            }
            val numbered = numberDiffLines(diffLines)
            return FileDiffModel(
                fileName = fileName,
                changeKind = kind,
                stats = statsOf(numbered),
                content = FileDiffContent.Lines(numbered, foldUnchangedSegments(numbered, limits.contextLines)),
            )
        }

        private fun summaryMaxD(limits: FileDiffLimits, lineSum: Int): Int =
            (limits.maxSummaryEditOps / (lineSum + 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        private fun inferKind(oldEmpty: Boolean, newEmpty: Boolean): FileChangeKind = when {
            oldEmpty && !newEmpty -> FileChangeKind.CREATED
            !oldEmpty && newEmpty -> FileChangeKind.DELETED
            else -> FileChangeKind.MODIFIED
        }
    }
}

/** 统计新增与删除行数。 */
fun statsOf(lines: List<NumberedDiffLine>): FileDiffStats =
    FileDiffStats(
        added = lines.count { it.kind == DiffLineKind.ADDED },
        removed = lines.count { it.kind == DiffLineKind.REMOVED },
    )

/** 按 Myers 行差异顺序推算行号：未变更行两侧递增，删除行只递增旧侧，新增行只递增新侧。 */
fun numberDiffLines(diffLines: List<MyersDiff.DiffLine>): List<NumberedDiffLine> {
    var oldNumber = 0
    var newNumber = 0
    return diffLines.map { line ->
        val kind = DiffLineKind.fromMyersType(line.type)
        val missingNewline = line.content.endsWith(MISSING_NEWLINE_SENTINEL)
        val content = if (missingNewline) line.content.dropLast(MISSING_NEWLINE_SENTINEL.length) else line.content
        when (kind) {
            DiffLineKind.CONTEXT -> NumberedDiffLine(kind, ++oldNumber, ++newNumber, content, missingNewline)
            DiffLineKind.REMOVED -> NumberedDiffLine(kind, ++oldNumber, null, content, missingNewline)
            DiffLineKind.ADDED -> NumberedDiffLine(kind, null, ++newNumber, content, missingNewline)
        }
    }
}

/** 切分后的文本行；[endsWithNewline] 表示原文以换行符结尾。 */
data class DiffTextLines(val lines: List<String>, val endsWithNewline: Boolean)

/** 按 `\n`、`\r\n`、`\r` 切分；空文本为 0 行，末尾换行不产生额外空行。 */
fun splitDiffText(text: String): DiffTextLines {
    if (text.isEmpty()) return DiffTextLines(emptyList(), endsWithNewline = false)
    val lines = ArrayList<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char != '\n' && char != '\r') {
            index++
            continue
        }
        lines += text.substring(start, index)
        index += if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') 2 else 1
        start = index
    }
    val endsWithNewline = start == text.length
    if (!endsWithNewline) lines += text.substring(start)
    return DiffTextLines(lines, endsWithNewline)
}

/**
 * 末尾换行状态与另一侧不同时，给本侧缺少末尾换行的最后一行追加哨兵，使其不与另一侧同内容的行相等，
 * 从而按一删一增呈现；[numberDiffLines] 会去掉哨兵并标记 [NumberedDiffLine.missingFinalNewline]。
 */
private fun DiffTextLines.taggedLines(other: DiffTextLines): List<String> {
    if (lines.isEmpty() || other.lines.isEmpty() || endsWithNewline || !other.endsWithNewline) return lines
    return lines.dropLast(1) + (lines.last() + MISSING_NEWLINE_SENTINEL)
}

/** 文本含 NUL 时整体按二进制处理，因此 NUL 可安全用作哨兵。 */
private const val MISSING_NEWLINE_SENTINEL = "\u0000"

private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
    val limit = minOf(a.size, b.size)
    var index = 0
    while (index < limit && a[index] == b[index]) index++
    return index
}

private fun commonSuffixLength(a: List<String>, b: List<String>, prefix: Int): Int {
    val limit = minOf(a.size, b.size) - prefix
    var count = 0
    while (count < limit && a[a.size - 1 - count] == b[b.size - 1 - count]) count++
    return count
}

/**
 * 线性空间的 Myers 前向搜索，只求最短编辑距离（新增数 + 删除数），不保留回溯表。
 * 距离超过 [maxD] 时返回空。
 */
internal fun editDistance(a: List<String>, b: List<String>, maxD: Int): Int? {
    val n = a.size
    val m = b.size
    if (n == 0 || m == 0) return (n + m).takeIf { it <= maxD }
    val ids = HashMap<String, Int>()
    val aIds = IntArray(n) { ids.getOrPut(a[it]) { ids.size } }
    val bIds = IntArray(m) { ids.getOrPut(b[it]) { ids.size } }
    val max = minOf(n + m, maxD)
    val offset = max + 1
    val v = IntArray(2 * max + 3)
    for (d in 0..max) {
        var k = -d
        while (k <= d) {
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                v[offset + k + 1]
            } else {
                v[offset + k - 1] + 1
            }
            var y = x - k
            while (x < n && y < m && aIds[x] == bIds[y]) {
                x++
                y++
            }
            v[offset + k] = x
            if (x >= n && y >= m) return d
            k += 2
        }
    }
    return null
}

/** 摘要计数：编辑距离在 [maxD] 内时精确换算，否则按行多重集合估算。 */
private fun summaryStats(oldLines: List<String>, newLines: List<String>, maxD: Int): FileDiffStats {
    val delta = newLines.size - oldLines.size
    editDistance(oldLines, newLines, maxD)?.let { d ->
        return FileDiffStats(added = (d + delta) / 2, removed = (d - delta) / 2)
    }
    val balance = HashMap<String, Int>()
    oldLines.forEach { balance[it] = (balance[it] ?: 0) - 1 }
    newLines.forEach { balance[it] = (balance[it] ?: 0) + 1 }
    var added = 0
    var removed = 0
    balance.values.forEach { if (it > 0) added += it else removed -= it }
    return FileDiffStats(added, removed, isApproximate = true)
}

/** 不分配字节数组的 UTF-8 长度计算。 */
internal fun utf8Length(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                index++
                4
            }
            else -> 3
        }
        index++
    }
    return bytes
}
