package com.promenar.nexara.infra.util

object MyersDiff {

    data class DiffLine(val type: String, val content: String)

    data class DiffHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<DiffLine>
    )

    private const val EQUAL = 0
    private const val INSERT = 1
    private const val DELETE = 2

    fun compute(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        val n = oldLines.size
        val m = newLines.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return newLines.map { DiffLine("added", it) }
        if (m == 0) return oldLines.map { DiffLine("removed", it) }

        val edits = computeEdits(oldLines, newLines)
        val result = mutableListOf<DiffLine>()

        var oi = 0
        var ni = 0
        for (edit in edits) {
            when (edit) {
                EQUAL -> {
                    result.add(DiffLine("context", oldLines[oi]))
                    oi++
                    ni++
                }
                INSERT -> {
                    result.add(DiffLine("added", newLines[ni]))
                    ni++
                }
                DELETE -> {
                    result.add(DiffLine("removed", oldLines[oi]))
                    oi++
                }
            }
        }

        return result
    }

    private fun computeEdits(oldLines: List<String>, newLines: List<String>): List<Int> {
        val n = oldLines.size
        val m = newLines.size
        val max = n + m
        val offset = max

        val vv = IntArray(2 * max + 1)
        vv[1 + offset] = 0

        val trace = mutableListOf<IntArray>()

        var finalD = max
        outer@ for (d in 0..max) {
            for (k in -d..d step 2) {
                var x: Int
                if (k == -d || (k != d && vv[k - 1 + offset] < vv[k + 1 + offset])) {
                    x = vv[k + 1 + offset]
                } else {
                    x = vv[k - 1 + offset] + 1
                }


                var y = x - k

                while (x < n && y < m && oldLines[x] == newLines[y]) {
                    x++
                    y++
                }

                vv[k + offset] = x

                if (x >= n && y >= m) {
                    trace.add(vv.copyOf())
                    finalD = d
                    break@outer
                }
            }
            trace.add(vv.copyOf())
        }

        val edits = mutableListOf<Int>()
        var x = n
        var y = m

        for (d in finalD downTo 0) {
            val v = trace[d]
            val k = x - y

            while (x > 0 && y > 0 && x - 1 < n && y - 1 < m && oldLines[x - 1] == newLines[y - 1]) {
                edits.add(EQUAL)
                x--
                y--
            }

            if (d == 0) break

            val prevV = trace[d - 1]
            val prevK: Int

            if (k == -d || (k != d && prevV[k - 1 + offset] < prevV[k + 1 + offset])) {
                prevK = k + 1
            } else {
                prevK = k - 1
            }

            if (prevK < k) {
                edits.add(DELETE)
                x--
            } else {
                edits.add(INSERT)
                y--
            }
        }

        while (x > 0 && y > 0) {
            edits.add(EQUAL)
            x--
            y--
        }
        while (x > 0) {
            edits.add(DELETE)
            x--
        }
        while (y > 0) {
            edits.add(INSERT)
            y--
        }

        edits.reverse()
        return edits
    }

    fun computeHunks(
        oldLines: List<String>,
        newLines: List<String>,
        contextLines: Int = 3
    ): List<DiffHunk> {
        val diffLines = compute(oldLines, newLines)
        if (diffLines.isEmpty()) return emptyList()

        val hasChanges = diffLines.any { it.type != "context" }
        if (!hasChanges) return emptyList()

        val changePositions = diffLines.mapIndexedNotNull { index, line ->
            if (line.type != "context") index else null
        }

        val groups = mutableListOf<List<Int>>()
        var currentGroup = mutableListOf(changePositions[0])

        for (i in 1 until changePositions.size) {
            val prevPos = changePositions[i - 1]
            val currPos = changePositions[i]
            val gapContextLines = (prevPos + 1 until currPos).count { diffLines[it].type == "context" }

            if (gapContextLines <= 2 * contextLines) {
                currentGroup.add(currPos)
            } else {
                groups.add(currentGroup)
                currentGroup = mutableListOf(currPos)
            }
        }
        groups.add(currentGroup)

        return groups.map { group ->
            val firstChange = group.first()
            val lastChange = group.last()

            val startIdx = maxOf(0, firstChange - contextLines)
            val endIdx = minOf(diffLines.size - 1, lastChange + contextLines)

            val hunkLines = diffLines.subList(startIdx, endIdx + 1)

            val oldCount = hunkLines.count { it.type == "context" || it.type == "removed" }
            val newCount = hunkLines.count { it.type == "context" || it.type == "added" }

            var oldStart = 1
            var newStart = 1
            for (j in 0 until startIdx) {
                when (diffLines[j].type) {
                    "context", "removed" -> oldStart++
                    "added" -> newStart++
                }
            }

            DiffHunk(
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = hunkLines
            )
        }
    }
}

fun String.lineDiff(other: String): List<MyersDiff.DiffLine> {
    return MyersDiff.compute(this.lines(), other.lines())
}
