package com.promenar.nexara.ui.common.diff

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.infra.util.MyersDiff
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("文件差异模型")
class FileDiffModelTest {

    private fun lines(range: IntRange, prefix: String = "line") = range.joinToString("\n") { "$prefix$it" } + "\n"

    private fun FileDiffModel.renderedLines(): List<NumberedDiffLine> =
        (content as FileDiffContent.Lines).lines

    private fun FileDiffModel.segments(): List<DiffSegment> =
        (content as FileDiffContent.Lines).segments

    @Nested
    @DisplayName("统计")
    inner class Stats {

        @Test
        @DisplayName("修改一行计为 +1 −1，行号按两侧分别递增")
        fun modifiedLine() {
            val model = FileDiffModel.fromTexts("a\nb\nc\n", "a\nB\nc\n", fileName = "x.txt")

            assertThat(model.stats).isEqualTo(FileDiffStats(added = 1, removed = 1))
            assertThat(model.changeKind).isEqualTo(FileChangeKind.MODIFIED)
            assertThat(model.countsLabel).isEqualTo("+1 −1")
            assertThat(model.renderedLines()).containsExactly(
                NumberedDiffLine(DiffLineKind.CONTEXT, 1, 1, "a"),
                NumberedDiffLine(DiffLineKind.REMOVED, 2, null, "b"),
                NumberedDiffLine(DiffLineKind.ADDED, null, 2, "B"),
                NumberedDiffLine(DiffLineKind.CONTEXT, 3, 3, "c"),
            ).inOrder()
        }

        @Test
        @DisplayName("统计与 MyersDiff 全量结果一致")
        fun matchesMyers() {
            val old = (1..40).map { "v$it" }
            val new = old.toMutableList().apply {
                removeAt(5)
                add(10, "inserted")
                set(20, "changed")
                add("tail")
            }
            val expected = MyersDiff.compute(old, new)
            val model = FileDiffModel.fromTexts(old.joinToString("\n"), new.joinToString("\n"))

            assertThat(model.stats!!.added).isEqualTo(expected.count { it.type == "added" })
            assertThat(model.stats!!.removed).isEqualTo(expected.count { it.type == "removed" })
            assertThat(model.renderedLines().map { it.content })
                .containsExactlyElementsIn(expected.map { it.content }).inOrder()
        }

        @Test
        @DisplayName("由已计算行差异构建时统计与行号一致")
        fun fromDiffLines() {
            val diff = MyersDiff.compute(listOf("a", "b"), listOf("a", "c", "d"))
            val model = FileDiffModel.fromDiffLines(diff, fileName = "y.kt")

            assertThat(model.stats).isEqualTo(FileDiffStats(added = 2, removed = 1))
            assertThat(model.renderedLines().single { it.content == "d" }).isEqualTo(NumberedDiffLine(DiffLineKind.ADDED, null, 3, "d"))
        }

        @Test
        @DisplayName("未知差异类型直接拒绝")
        fun unknownType() {
            assertThrows<IllegalArgumentException> {
                FileDiffModel.fromDiffLines(listOf(MyersDiff.DiffLine("moved", "x")))
            }
        }
    }

    @Nested
    @DisplayName("边界输入")
    inner class EdgeInputs {

        @Test
        @DisplayName("两侧均为空文本时无行、无变更")
        fun bothEmpty() {
            val model = FileDiffModel.fromTexts("", "")

            assertThat(model.stats).isEqualTo(FileDiffStats(0, 0))
            assertThat(model.changeKind).isEqualTo(FileChangeKind.MODIFIED)
            assertThat(model.renderedLines()).isEmpty()
            assertThat(model.segments()).isEmpty()
        }

        @Test
        @DisplayName("只增：新建文件头部只显示 +")
        fun onlyAdded() {
            val model = FileDiffModel.fromTexts("", "x\ny\nz\n")

            assertThat(model.changeKind).isEqualTo(FileChangeKind.CREATED)
            assertThat(model.stats).isEqualTo(FileDiffStats(added = 3, removed = 0))
            assertThat(model.headerAdded).isEqualTo(3)
            assertThat(model.headerRemoved).isNull()
            assertThat(model.countsLabel).isEqualTo("+3")
            assertThat(model.renderedLines().map { it.newLineNumber }).containsExactly(1, 2, 3).inOrder()
            assertThat(model.renderedLines().all { it.oldLineNumber == null }).isTrue()
        }

        @Test
        @DisplayName("只删：删除文件头部只显示 −")
        fun onlyRemoved() {
            val model = FileDiffModel.fromTexts("x\ny\n", "")

            assertThat(model.changeKind).isEqualTo(FileChangeKind.DELETED)
            assertThat(model.stats).isEqualTo(FileDiffStats(added = 0, removed = 2))
            assertThat(model.countsLabel).isEqualTo("−2")
            assertThat(model.renderedLines().map { it.kind }).containsExactly(DiffLineKind.REMOVED, DiffLineKind.REMOVED)
        }

        @Test
        @DisplayName("显式指定的变更类型优先于推断")
        fun explicitKind() {
            val model = FileDiffModel.fromTexts("", "a\n", changeKind = FileChangeKind.MODIFIED)

            assertThat(model.countsLabel).isEqualTo("+1 −0")
        }

        @Test
        @DisplayName("末尾换行不产生额外空行，CRLF 与 LF 等价")
        fun trailingNewlineSplitting() {
            assertThat(splitDiffText("a\nb\n")).isEqualTo(DiffTextLines(listOf("a", "b"), endsWithNewline = true))
            assertThat(splitDiffText("a\r\nb")).isEqualTo(DiffTextLines(listOf("a", "b"), endsWithNewline = false))
            assertThat(splitDiffText("\n")).isEqualTo(DiffTextLines(listOf(""), endsWithNewline = true))
            assertThat(splitDiffText("")).isEqualTo(DiffTextLines(emptyList(), endsWithNewline = false))
            assertThat(FileDiffModel.fromTexts("a\r\nb\r\n", "a\nb\n").stats).isEqualTo(FileDiffStats(0, 0))
        }

        @Test
        @DisplayName("仅末尾换行变化时按一删一增呈现并标记缺失一侧")
        fun missingFinalNewline() {
            val model = FileDiffModel.fromTexts("a\nb\n", "a\nb")

            assertThat(model.stats).isEqualTo(FileDiffStats(added = 1, removed = 1))
            assertThat(model.renderedLines()).containsExactly(
                NumberedDiffLine(DiffLineKind.CONTEXT, 1, 1, "a"),
                NumberedDiffLine(DiffLineKind.REMOVED, 2, null, "b"),
                NumberedDiffLine(DiffLineKind.ADDED, null, 2, "b", missingFinalNewline = true),
            ).inOrder()
        }

        @Test
        @DisplayName("两侧都无末尾换行时不产生伪变更")
        fun bothWithoutFinalNewline() {
            val model = FileDiffModel.fromTexts("a\nb", "a\nc")

            assertThat(model.stats).isEqualTo(FileDiffStats(added = 1, removed = 1))
            assertThat(model.renderedLines().none { it.missingFinalNewline }).isTrue()
            assertThat(model.renderedLines().map { it.content }).containsExactly("a", "b", "c").inOrder()
        }

        @Test
        @DisplayName("二进制标记或含 NUL 的文本降级为二进制且无统计")
        fun binary() {
            val flagged = FileDiffModel.fromTexts("a", "b", fileName = "logo.png", isBinary = true)
            val sniffed = FileDiffModel.fromTexts("a\u0000b", "a")

            assertThat(flagged.content).isEqualTo(FileDiffContent.Binary)
            assertThat(flagged.countsLabel).isNull()
            assertThat(sniffed.content).isEqualTo(FileDiffContent.Binary)
            assertThat(sniffed.stats).isNull()
        }
    }

    @Nested
    @DisplayName("超大文件阈值")
    inner class LargeFiles {

        @Test
        @DisplayName("恰好 5000 行仍逐行渲染")
        fun atLineLimit() {
            val model = FileDiffModel.fromTexts("", lines(1..5_000))

            assertThat(model.content).isInstanceOf(FileDiffContent.Lines::class.java)
            assertThat(model.stats).isEqualTo(FileDiffStats(added = 5_000, removed = 0))
        }

        @Test
        @DisplayName("任一侧超过 5000 行只给精确摘要")
        fun overLineLimit() {
            val old = lines(1..5_001)
            val new = old.replace("line100\n", "changed\n")
            val model = FileDiffModel.fromTexts(old, new)

            assertThat(model.content).isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
            assertThat(model.stats).isEqualTo(FileDiffStats(added = 1, removed = 1))
            assertThat(model.countsLabel).isEqualTo("+1 −1")
        }

        @Test
        @DisplayName("任一侧超过 512 KiB 只给摘要，恰好 512 KiB 仍逐行渲染")
        fun byteLimit() {
            val exact = "x".repeat(512 * 1024 - 1) + "\n"
            val over = "x".repeat(512 * 1024) + "\n"

            assertThat(FileDiffModel.fromTexts("", exact).content).isInstanceOf(FileDiffContent.Lines::class.java)
            assertThat(FileDiffModel.fromTexts("", over).content)
                .isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
        }

        @Test
        @DisplayName("多字节字符按 UTF-8 字节计数")
        fun utf8Bytes() {
            assertThat(utf8Length("aé中😀")).isEqualTo(1L + 2L + 3L + 4L)
            val cjk = "中".repeat(180_000)
            assertThat(FileDiffModel.fromTexts(cjk, "").content)
                .isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
        }

        @Test
        @DisplayName("已计算行差异同样受行数阈值约束")
        fun fromDiffLinesThreshold() {
            val diff = (1..5_001).map { MyersDiff.DiffLine("added", "n$it") }
            val model = FileDiffModel.fromDiffLines(diff)

            assertThat(model.content).isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
            assertThat(model.countsLabel).isEqualTo("+5001")
        }

        @Test
        @DisplayName("编辑距离超出回溯表预算时降级为复杂变更摘要，计数仍精确")
        fun tooComplex() {
            val limits = FileDiffLimits(maxEditTraceCells = 1_000)
            val model = FileDiffModel.fromTexts(lines(1..30, "a"), lines(1..30, "b"), limits = limits)

            assertThat(model.content).isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.TOO_COMPLEX))
            assertThat(model.stats).isEqualTo(FileDiffStats(added = 30, removed = 30))
        }

        @Test
        @DisplayName("摘要计算量超出预算时改用多重集合估算并标记近似")
        fun approximateSummary() {
            val limits = FileDiffLimits(maxLinesPerSide = 10, maxSummaryEditOps = 10)
            val model = FileDiffModel.fromTexts(lines(1..20, "a"), lines(1..25, "b"), limits = limits)

            assertThat(model.stats).isEqualTo(FileDiffStats(added = 25, removed = 20, isApproximate = true))
        }

        @Test
        @DisplayName("线性空间编辑距离与 MyersDiff 的增删总数一致")
        fun editDistanceMatchesMyers() {
            val a = listOf("a", "b", "c", "a", "b", "b", "a")
            val b = listOf("c", "b", "a", "b", "a", "c")
            val myers = MyersDiff.compute(a, b).count { it.type != "context" }

            assertThat(editDistance(a, b, maxD = 100)).isEqualTo(myers)
            assertThat(editDistance(a, b, maxD = myers - 1)).isNull()
        }
    }

    @Nested
    @DisplayName("折叠划分")
    inner class Folding {

        @Test
        @DisplayName("变更前后各保留 3 行，中间与两端多余的未变更行折叠")
        fun foldsAroundChange() {
            val old = lines(1..20)
            val new = old.replace("line10\n", "changed\n")
            val segments = FileDiffModel.fromTexts(old, new).segments()

            assertThat(segments.map { it::class }).containsExactly(
                DiffSegment.Folded::class,
                DiffSegment.Shown::class,
                DiffSegment.Folded::class,
            ).inOrder()
            assertThat(segments[0].lines.map { it.newLineNumber }).containsExactlyElementsIn(1..6).inOrder()
            assertThat(segments[1].lines.map { it.content })
                .containsExactly("line7", "line8", "line9", "line10", "changed", "line11", "line12", "line13").inOrder()
            assertThat(segments[2].lines.map { it.oldLineNumber }).containsExactlyElementsIn(14..20).inOrder()
        }

        @Test
        @DisplayName("两处变更间未变更行不超过 6 行时不折叠，超过时只折叠中间部分")
        fun gapBetweenChanges() {
            fun changedAt(vararg indices: Int): List<DiffSegment> {
                val base = (1..30).map { "l$it" }
                val changed = base.mapIndexed { index, line -> if (index + 1 in indices) "x$line" else line }
                return FileDiffModel.fromTexts(base.joinToString("\n"), changed.joinToString("\n")).segments()
            }

            val merged = changedAt(10, 17)
            assertThat(merged.count { it is DiffSegment.Shown }).isEqualTo(1)

            val split = changedAt(10, 18)
            val middleFold = split.filterIsInstance<DiffSegment.Folded>()
                .single { fold -> fold.lines.first().oldLineNumber == 14 }
            assertThat(middleFold.lines).hasSize(1)
        }

        @Test
        @DisplayName("区段拼接后与原始行完全一致")
        fun segmentsCoverAllLines() {
            val model = FileDiffModel.fromTexts(lines(1..50), lines(1..50).replace("line5\n", "").replace("line40\n", "x\n"))
            val content = model.content as FileDiffContent.Lines

            assertThat(content.segments.flatMap { it.lines }).containsExactlyElementsIn(content.lines).inOrder()
        }

        @Test
        @DisplayName("无变更时整段折叠；短文件不折叠")
        fun noChanges() {
            val unchanged = FileDiffModel.fromTexts(lines(1..10), lines(1..10)).segments()
            assertThat(unchanged).hasSize(1)
            assertThat(unchanged.single()).isInstanceOf(DiffSegment.Folded::class.java)

            val short = FileDiffModel.fromTexts("a\nb\nc\n", "a\nb\nd\n").segments()
            assertThat(short).hasSize(1)
            assertThat(short.single()).isInstanceOf(DiffSegment.Shown::class.java)
        }

        @Test
        @DisplayName("上下文行数为 0 时只显示变更行")
        fun zeroContext() {
            val numbered = numberDiffLines(MyersDiff.compute(listOf("a", "b", "c"), listOf("a", "x", "c")))
            val segments = foldUnchangedSegments(numbered, contextLines = 0)

            assertThat(segments.map { it::class }).containsExactly(
                DiffSegment.Folded::class,
                DiffSegment.Shown::class,
                DiffSegment.Folded::class,
            ).inOrder()
            assertThat(segments[1].lines.map { it.kind }).containsExactly(DiffLineKind.REMOVED, DiffLineKind.ADDED)
        }
    }

    @Nested
    @DisplayName("并排配对")
    inner class Pairing {

        @Test
        @DisplayName("删除与新增逐一配对，多出的一侧对面留空，未变更行左右相同")
        fun pairsChangeBlocks() {
            val lines = FileDiffModel.fromTexts("a\nb\nc\nd\n", "a\nB\nC\nX\nd\n").renderedLines()
            val rows = pairSideBySide(lines)

            assertThat(rows.map { it.old?.content to it.new?.content }).containsExactly(
                "a" to "a",
                "b" to "B",
                "c" to "C",
                null to "X",
                "d" to "d",
            ).inOrder()
            assertThat(rows.first().old).isSameInstanceAs(rows.first().new)
        }

        @Test
        @DisplayName("只删除时右侧全部留空，行号保持旧侧编号")
        fun onlyRemovedPairs() {
            val rows = pairSideBySide(FileDiffModel.fromTexts("a\nb\n", "").renderedLines())

            assertThat(rows.map { it.old?.oldLineNumber }).containsExactly(1, 2).inOrder()
            assertThat(rows.all { it.new == null }).isTrue()
        }

        @Test
        @DisplayName("配对不丢失任何行")
        fun preservesAllLines() {
            val lines = FileDiffModel.fromTexts(lines(1..30), lines(5..40)).renderedLines()
            val rows = pairSideBySide(lines)
            val oldSide = rows.mapNotNull { it.old }.distinct()
            val newSide = rows.mapNotNull { it.new }.distinct()

            assertThat(oldSide).containsExactlyElementsIn(lines.filter { it.kind != DiffLineKind.ADDED }).inOrder()
            assertThat(newSide).containsExactlyElementsIn(lines.filter { it.kind != DiffLineKind.REMOVED }).inOrder()
        }
    }

    @Nested
    @DisplayName("显示文本")
    inner class DisplayText {

        @Test
        @DisplayName("制表符展开、超长行截断且不拆开代理对")
        fun displayText() {
            assertThat(diffDisplayText("\tx")).isEqualTo("    x")
            assertThat(diffDisplayText("abcdef", maxChars = 4)).isEqualTo("abcd…")
            assertThat(diffDisplayText("abc😀", maxChars = 4)).isEqualTo("abc…")
        }

        @Test
        @DisplayName("最宽候选按估算列宽挑选，全角字符按 2 列计")
        fun widestCandidates() {
            val lines = listOf("short", "中文中文中文", "longer-ascii-x", "x").mapIndexed { index, text ->
                NumberedDiffLine(DiffLineKind.CONTEXT, index + 1, index + 1, text)
            }

            assertThat(widestDisplayCandidates(lines, count = 2)).containsExactly("longer-ascii-x", "中文中文中文").inOrder()
        }
    }
}
