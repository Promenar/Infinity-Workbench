package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

/** 执行步骤新增的 fileChanges 字段：旧 JSON 可解码、为空时不写出、取默认值的可选字段不写出。 */
class FileChangeSerializationTest {
    private val steps = ListSerializer(ExecutionStep.serializer())

    @Test
    fun `旧执行步骤 JSON 没有 fileChanges 时按空解码`() {
        val legacy = """[{"id":"res_1","type":"tool_result","toolName":"write_file","toolCallId":"c1","content":"写入成功","data":null,"timestamp":5,"throttledUntil":null,"taskStepId":null}]"""

        val decoded = json.decodeFromString(steps, legacy)

        assertThat(decoded.single().fileChanges).isNull()
        assertThat(decoded.single().content).isEqualTo("写入成功")
    }

    @Test
    fun `fileChanges 为空时不写入 JSON`() {
        val encoded = json.encodeToString(steps, listOf(ExecutionStep(id = "s", type = "tool_call", timestamp = 1)))
        val result = json.encodeToString(ToolResult.serializer(), ToolResult("r", "ok"))

        assertThat(encoded).doesNotContain("fileChanges")
        assertThat(result).doesNotContain("fileChanges")
    }

    @Test
    fun `改动摘要只写出有值的字段并可往返`() {
        val step = ExecutionStep(
            id = "res_1",
            type = "tool_result",
            timestamp = 1,
            fileChanges = listOf(
                FileChangeSummary(
                    path = "/src/a.kt",
                    change = FileChangeType.MODIFIED,
                    added = 2,
                    removed = 1,
                    hunks = listOf(FileChangeHunk(3, 2, 3, 3, listOf(" keep", "-old", "+new", "+more", "\\"))),
                ),
                FileChangeSummary(path = "/b.txt", change = FileChangeType.MOVED, fromPath = "/a.txt", added = 0, removed = 0),
            ),
        )

        val encoded = json.encodeToString(steps, listOf(step))

        assertThat(encoded).contains(
            """"fileChanges":[{"path":"/src/a.kt","change":"modified","added":2,"removed":1,""" +
                """"hunks":[{"oldStart":3,"oldCount":2,"newStart":3,"newCount":3,"lines":[" keep","-old","+new","+more","\\"]}]}""",
        )
        assertThat(encoded).contains(""""change":"moved","fromPath":"/a.txt"""")
        assertThat(encoded).doesNotContain("binary")
        assertThat(encoded).doesNotContain("hunksOmitted")
        assertThat(json.decodeFromString(steps, encoded).single()).isEqualTo(step)
    }

    @Test
    fun `新版本增加的未知字段被忽略而不影响其它字段`() {
        val future = """[{"id":"s","type":"tool_result","timestamp":1,"fileChanges":[{"path":"/a","change":"created","added":3,"futureField":true}]}]"""

        val decoded = json.decodeFromString(steps, future).single().fileChanges!!.single()

        assertThat(decoded.change).isEqualTo(FileChangeType.CREATED)
        assertThat(decoded.added).isEqualTo(3)
        assertThat(decoded.removed).isNull()
    }

    @Test
    fun `总上限按顺序保留 hunk 并丢弃超出部分`() {
        fun summary(path: String, bytes: Int) = FileChangeSummary(
            path = path,
            change = FileChangeType.CREATED,
            added = 1,
            hunks = listOf(FileChangeHunk(1, 0, 1, 1, listOf("+" + "x".repeat(bytes - 1)))),
        )
        val changes = listOf("/a", "/b", "/c", "/d", "/e").map { summary(it, 30 * 1024) } + summary("/f", 40 * 1024)

        val limited = changes.withinHunkBudget()

        assertThat(limited.take(4).all { it.hunks != null }).isTrue()
        assertThat(limited[4].hunks).isNull()
        assertThat(limited[4].hunksOmitted).isEqualTo(FileChangeHunkOmission.SIZE_LIMIT)
        assertThat(limited[4].added).isEqualTo(1)
        // 单文件超过 32 KiB 的 hunk 无论剩余额度都不保存。
        assertThat(limited[5].hunks).isNull()
        assertThat(limited.sumOf { it.storedHunkBytes }).isAtMost(FileChangeSummary.MAX_RESULT_HUNK_BYTES)
    }
}
