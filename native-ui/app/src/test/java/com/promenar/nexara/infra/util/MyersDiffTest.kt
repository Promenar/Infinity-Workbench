package com.promenar.nexara.infra.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MyersDiff")
class MyersDiffTest {

    @Nested
    @DisplayName("identical content")
    inner class IdenticalContent {

        @Test
        @DisplayName("produces all context lines")
        fun allContext() {
            val old = listOf("line1", "line2", "line3")
            val new = listOf("line1", "line2", "line3")
            val result = MyersDiff.compute(old, new)

            assertThat(result).hasSize(3)
            result.forEach { line ->
                assertThat(line.type).isEqualTo("context")
            }
            assertThat(result[0].content).isEqualTo("line1")
            assertThat(result[1].content).isEqualTo("line2")
            assertThat(result[2].content).isEqualTo("line3")
        }

        @Test
        @DisplayName("single identical line")
        fun singleLine() {
            val result = MyersDiff.compute(listOf("hello"), listOf("hello"))
            assertThat(result).hasSize(1)
            assertThat(result[0].type).isEqualTo("context")
            assertThat(result[0].content).isEqualTo("hello")
        }
    }

    @Nested
    @DisplayName("single line change")
    inner class SingleLineChange {

        @Test
        @DisplayName("one removed + one added")
        fun oneRemovedOneAdded() {
            val old = listOf("line1", "line2", "line3")
            val new = listOf("line1", "modified", "line3")
            val result = MyersDiff.compute(old, new)

            val removed = result.filter { it.type == "removed" }
            val added = result.filter { it.type == "added" }
            val context = result.filter { it.type == "context" }

            assertThat(removed).hasSize(1)
            assertThat(added).hasSize(1)
            assertThat(context).hasSize(2)

            assertThat(removed[0].content).isEqualTo("line2")
            assertThat(added[0].content).isEqualTo("modified")
        }

        @Test
        @DisplayName("completely different single line")
        fun completelyDifferent() {
            val old = listOf("alpha")
            val new = listOf("beta")
            val result = MyersDiff.compute(old, new)

            assertThat(result).hasSize(2)
            assertThat(result[0].type).isEqualTo("removed")
            assertThat(result[0].content).isEqualTo("alpha")
            assertThat(result[1].type).isEqualTo("added")
            assertThat(result[1].content).isEqualTo("beta")
        }
    }

    @Nested
    @DisplayName("multi-line insertions and deletions")
    inner class MultiLineChanges {

        @Test
        @DisplayName("insert multiple lines in middle")
        fun insertMiddle() {
            val old = listOf("a", "d")
            val new = listOf("a", "b", "c", "d")
            val result = MyersDiff.compute(old, new)

            val added = result.filter { it.type == "added" }.map { it.content }
            assertThat(added).containsExactly("b", "c")
        }

        @Test
        @DisplayName("delete multiple lines from middle")
        fun deleteMiddle() {
            val old = listOf("a", "b", "c", "d")
            val new = listOf("a", "d")
            val result = MyersDiff.compute(old, new)

            val removed = result.filter { it.type == "removed" }.map { it.content }
            assertThat(removed).containsExactly("b", "c")
        }

        @Test
        @DisplayName("insert at beginning")
        fun insertBeginning() {
            val old = listOf("b", "c")
            val new = listOf("a", "b", "c")
            val result = MyersDiff.compute(old, new)

            val added = result.filter { it.type == "added" }.map { it.content }
            assertThat(added).containsExactly("a")
        }

        @Test
        @DisplayName("insert at end")
        fun insertEnd() {
            val old = listOf("a", "b")
            val new = listOf("a", "b", "c")
            val result = MyersDiff.compute(old, new)

            val added = result.filter { it.type == "added" }.map { it.content }
            assertThat(added).containsExactly("c")
        }

        @Test
        @DisplayName("replace block of lines")
        fun replaceBlock() {
            val old = listOf("header", "old1", "old2", "footer")
            val new = listOf("header", "new1", "new2", "new3", "footer")
            val result = MyersDiff.compute(old, new)

            assertThat(result.first().type).isEqualTo("context")
            assertThat(result.first().content).isEqualTo("header")
            assertThat(result.last().type).isEqualTo("context")
            assertThat(result.last().content).isEqualTo("footer")
        }
    }

    @Nested
    @DisplayName("empty inputs")
    inner class EmptyInputs {

        @Test
        @DisplayName("both empty returns empty diff")
        fun bothEmpty() {
            val result = MyersDiff.compute(emptyList(), emptyList())
            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("old empty + new has lines = all added")
        fun oldEmpty() {
            val result = MyersDiff.compute(emptyList(), listOf("a", "b", "c"))
            assertThat(result).hasSize(3)
            result.forEach { line ->
                assertThat(line.type).isEqualTo("added")
            }
        }

        @Test
        @DisplayName("new empty + old has lines = all removed")
        fun newEmpty() {
            val result = MyersDiff.compute(listOf("x", "y"), emptyList())
            assertThat(result).hasSize(2)
            result.forEach { line ->
                assertThat(line.type).isEqualTo("removed")
            }
        }
    }

    @Nested
    @DisplayName("String.lineDiff extension")
    inner class LineDiffExtension {

        @Test
        @DisplayName("splits strings and computes diff")
        fun extensionWorks() {
            val old = "line1\nline2\nline3"
            val new = "line1\nmodified\nline3"
            val result = old.lineDiff(new)

            assertThat(result.filter { it.type == "removed" }).hasSize(1)
            assertThat(result.filter { it.type == "added" }).hasSize(1)
        }
    }

    @Nested
    @DisplayName("computeHunks")
    inner class ComputeHunks {

        @Test
        @DisplayName("identical content produces no hunks")
        fun identicalNoHunks() {
            val hunks = MyersDiff.computeHunks(
                listOf("a", "b", "c"),
                listOf("a", "b", "c")
            )
            assertThat(hunks).isEmpty()
        }

        @Test
        @DisplayName("single change produces single hunk with context")
        fun singleChangeSingleHunk() {
            val old = listOf("a", "b", "c", "d", "e", "f", "g")
            val new = listOf("a", "b", "X", "d", "e", "f", "g")
            val hunks = MyersDiff.computeHunks(old, new, contextLines = 3)

            assertThat(hunks).hasSize(1)
            val hunk = hunks[0]
            assertThat(hunk.oldStart).isEqualTo(1)
            assertThat(hunk.newStart).isEqualTo(1)
            assertThat(hunk.lines.any { it.type == "removed" }).isTrue()
            assertThat(hunk.lines.any { it.type == "added" }).isTrue()
        }

        @Test
        @DisplayName("distant changes produce separate hunks")
        fun distantChangesSeparateHunks() {
            val old = (1..20).map { "line$it" }
            val new = old.toMutableList().apply {
                this[1] = "CHANGED_A"
                this[17] = "CHANGED_B"
            }
            val hunks = MyersDiff.computeHunks(old, new, contextLines = 3)

            assertThat(hunks.size).isAtLeast(2)
        }
    }
}
