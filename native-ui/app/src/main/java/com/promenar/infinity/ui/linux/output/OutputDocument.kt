package com.promenar.infinity.ui.linux.output

/** 查看器的三个分段。 */
enum class OutputSegment { MERGED, STDOUT, STDERR }

/** 合并视图中 stderr 段的范围，供界面以错误色区分。 */
data class OutputDocument(
    val ansi: AnsiDocument,
    /** 每一行在 [AnsiDocument.text] 中的范围（不含换行符）。 */
    val lines: List<IntRange>,
    /** stderr 内容在合并视图里的范围；其它分段为 null。 */
    val stderrRange: IntRange?,
    /** 分隔行（“stderr”标题）的范围；没有分隔行时为 null。 */
    val separatorRange: IntRange?,
) {
    val text: String get() = ansi.text

    /** 二分查找 [offset] 所在的行号。 */
    fun lineOf(offset: Int): Int {
        if (lines.isEmpty()) return 0
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lines[mid].first <= offset) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        val EMPTY = of(AnsiDocument.EMPTY)

        fun of(ansi: AnsiDocument, stderrRange: IntRange? = null, separatorRange: IntRange? = null) =
            OutputDocument(ansi, splitLines(ansi.text), stderrRange, separatorRange)
    }
}

/**
 * 生成各分段的文档。命令结果只分别保存了 stdout 与 stderr，无法还原交错顺序，
 * 所以合并视图依次显示 stdout、分隔行与 stderr；任一路为空时不加分隔行。
 */
object OutputSegments {
    fun build(output: CommandOutput, segment: OutputSegment, stderrSeparator: String): OutputDocument {
        val stdout = AnsiParser.parse(output.stdout.raw)
        val stderr = AnsiParser.parse(output.stderr.raw)
        return when (segment) {
            OutputSegment.STDOUT -> OutputDocument.of(stdout)
            OutputSegment.STDERR -> OutputDocument.of(stderr)
            OutputSegment.MERGED -> merge(stdout, stderr, stderrSeparator)
        }
    }

    fun merge(stdout: AnsiDocument, stderr: AnsiDocument, stderrSeparator: String): OutputDocument {
        if (stderr.text.isEmpty()) return OutputDocument.of(stdout)
        if (stdout.text.isEmpty()) return OutputDocument.of(stderr, stderrRange = stderr.text.indices)
        val head = if (stdout.text.endsWith('\n')) stdout else concatAnsi(listOf(stdout, AnsiDocument("\n", emptyList())))
        val separator = AnsiDocument("$stderrSeparator\n", emptyList())
        val merged = concatAnsi(listOf(head, separator, stderr))
        val separatorStart = head.text.length
        val stderrStart = separatorStart + separator.text.length
        return OutputDocument.of(
            merged,
            stderrRange = stderrStart until merged.text.length,
            separatorRange = separatorStart until stderrStart - 1,
        )
    }
}

/** 末尾换行不产生额外空行；空文本视为没有行。 */
internal fun splitLines(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val lines = ArrayList<IntRange>()
    var start = 0
    while (true) {
        val end = text.indexOf('\n', start)
        if (end < 0) {
            if (start < text.length) lines += start until text.length
            break
        }
        lines += start until end
        start = end + 1
        if (start >= text.length) break
    }
    return lines
}

/** 搜索命中与当前位置；[current] 在没有命中时为 -1。 */
data class OutputSearch(
    val query: String = "",
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
) {
    val currentMatch: IntRange? get() = matches.getOrNull(current)

    fun next(): OutputSearch = if (matches.isEmpty()) this else copy(current = (current + 1).mod(matches.size))

    fun previous(): OutputSearch = if (matches.isEmpty()) this else copy(current = (current - 1).mod(matches.size))

    companion object {
        /** 命中上限，避免单字符查询在大输出里产生过多高亮。 */
        const val MAX_MATCHES = 5_000

        /** 不区分大小写，命中互不重叠。 */
        fun find(text: String, query: String, keepNear: Int? = null): OutputSearch {
            if (query.isEmpty()) return OutputSearch(query)
            val matches = ArrayList<IntRange>()
            var from = 0
            while (matches.size < MAX_MATCHES) {
                val index = text.indexOf(query, from, ignoreCase = true)
                if (index < 0) break
                matches += index until index + query.length
                from = index + query.length
            }
            if (matches.isEmpty()) return OutputSearch(query)
            // 改查询或切换分段时，尽量停在原位置之后的第一个命中。
            val current = keepNear?.let { anchor -> matches.indexOfFirst { it.first >= anchor }.takeIf { it >= 0 } } ?: 0
            return OutputSearch(query, matches, current)
        }
    }
}
