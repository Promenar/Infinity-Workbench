package com.promenar.infinity.ui.common.diff

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FileDiffHunksTest {
    private fun line(kind: DiffLineKind, content: String, missing: Boolean = false) = DiffHunkLine(kind, content, missing)

    @Test
    fun `hunk 行号从起始行号顺序推算`() {
        val model = FileDiffModel.fromHunks(
            hunks = listOf(
                DiffHunkInput(
                    oldStart = 10,
                    newStart = 12,
                    lines = listOf(
                        line(DiffLineKind.CONTEXT, "a"),
                        line(DiffLineKind.REMOVED, "b"),
                        line(DiffLineKind.ADDED, "B"),
                        line(DiffLineKind.ADDED, "C", missing = true),
                    ),
                ),
            ),
            changeKind = FileChangeKind.MODIFIED,
        )

        val content = model.content as FileDiffContent.Lines
        assertThat(content.lines.map { it.oldLineNumber to it.newLineNumber })
            .containsExactly(10 to 12, 11 to null, null to 13, null to 14).inOrder()
        assertThat(content.lines.last().missingFinalNewline).isTrue()
        assertThat(model.stats).isEqualTo(FileDiffStats(added = 2, removed = 1))
        // 首个 hunk 之前的未变更行：旧侧 9 行、新侧 11 行不一致时行数未知。
        assertThat(content.segments.first()).isEqualTo(OmittedDiffSegment(1, 1, null))
    }

    @Test
    fun `相邻 hunk 之间的未变更行数来自起始行号`() {
        val model = FileDiffModel.fromHunks(
            hunks = listOf(
                DiffHunkInput(1, 1, listOf(line(DiffLineKind.REMOVED, "x"), line(DiffLineKind.CONTEXT, "k"))),
                DiffHunkInput(8, 7, listOf(line(DiffLineKind.ADDED, "y"))),
            ),
            changeKind = FileChangeKind.MODIFIED,
            stats = FileDiffStats(5, 6, isApproximate = true),
        )

        val segments = (model.content as FileDiffContent.Lines).segments
        assertThat(segments).hasSize(3)
        assertThat(segments[1]).isEqualTo(OmittedDiffSegment(oldStart = 3, newStart = 2, lineCount = 5))
        assertThat(segments[2].lines.single().newLineNumber).isEqualTo(7)
        assertThat(model.stats).isEqualTo(FileDiffStats(5, 6, isApproximate = true))
        // 折叠区段不含行：拼接区段的行仍等于全部行。
        assertThat(segments.flatMap { it.lines }).isEqualTo((model.content as FileDiffContent.Lines).lines)
    }

    @Test
    fun `乱序或重叠的 hunk 被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            FileDiffModel.fromHunks(
                listOf(
                    DiffHunkInput(5, 5, listOf(line(DiffLineKind.CONTEXT, "a"), line(DiffLineKind.CONTEXT, "b"))),
                    DiffHunkInput(6, 6, listOf(line(DiffLineKind.ADDED, "c"))),
                ),
                FileChangeKind.MODIFIED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileDiffModel.fromHunks(listOf(DiffHunkInput(0, 1, emptyList())), FileChangeKind.MODIFIED)
        }
    }
}
