package com.promenar.infinity.ui.chat.diff

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.chat.toolGroupFileChanges
import com.promenar.infinity.ui.chat.toolGroupSummary
import com.promenar.infinity.ui.chat.toolInvocations
import org.junit.Test

class FileChangePresentationTest {
    private fun call(id: String, name: String) = ExecutionStep(id = "step_$id", type = "tool_call", toolName = name, toolCallId = id, timestamp = 1)

    @Test
    fun `只取成功结果中的改动摘要且历史结果保持为空`() {
        val change = FileChangeSummary("/a.kt", FileChangeType.MODIFIED, added = 1, removed = 2)
        val steps = listOf(
            call("w", "write_file"),
            ExecutionStep(id = "res_step_w", type = "tool_result", toolName = "write_file", toolCallId = "w", timestamp = 2, fileChanges = listOf(change)),
            call("legacy", "patch_file"),
            ExecutionStep(id = "res_step_legacy", type = "tool_result", toolName = "patch_file", toolCallId = "legacy", timestamp = 3),
            call("bad", "delete_file"),
            ExecutionStep(id = "res_step_bad", type = "error", toolName = "delete_file", toolCallId = "bad", timestamp = 4, fileChanges = listOf(change)),
        )

        val invocations = toolInvocations(steps)

        assertThat(toolGroupFileChanges(invocations)).containsExactly(change)
        assertThat(invocations.map { it.fileChanges.size }).containsExactly(1, 0, 0).inOrder()
        assertThat(toolGroupSummary(invocations).isFileEditOnly).isTrue()
    }

    @Test
    fun `私有工作区的命令结果列出改动文件`() {
        val content = """{"exit_code":0,"stdout":"","workspace_changes":{"added":["/new.txt"],"modified":["/a.kt"],"removed":[],""" +
            """"moved":[{"from":"/old.md","to":"/docs/old.md"}],"counts":{"added":1,"modified":1,"removed":0,"moved":1},"truncated":false}}"""
        val steps = listOf(
            call("sh", "shell_exec"),
            ExecutionStep(id = "res_step_sh", type = "tool_result", toolName = "shell_exec", toolCallId = "sh", content = content, timestamp = 2),
        )

        val changes = toolInvocations(steps).single().workspaceChanges!!

        assertThat(changes.entries).containsExactly(
            WorkspaceChangeEntry(FileChangeType.CREATED, "/new.txt"),
            WorkspaceChangeEntry(FileChangeType.MODIFIED, "/a.kt"),
            WorkspaceChangeEntry(FileChangeType.MOVED, "/docs/old.md", "/old.md"),
        ).inOrder()
        assertThat(changes.truncated).isFalse()
    }

    @Test
    fun `自选工作区与无改动的命令结果不显示改动列表`() {
        assertThat(parseWorkspaceChanges("""{"exit_code":0,"workspace_kind":"project","workspace_tracking":"none"}""")).isNull()
        assertThat(parseWorkspaceChanges("""{"exit_code":0,"workspace_changes":null}""")).isNull()
        assertThat(parseWorkspaceChanges("""{"workspace_changes":{"added":[],"modified":[],"removed":[],"moved":[],"truncated":false}}""")).isNull()
        assertThat(parseWorkspaceChanges("工具执行失败：退出码 1")).isNull()
        assertThat(parseWorkspaceChanges("""{"workspace_changes":{"added":[],"truncated":true}}""")!!.truncated).isTrue()

        val other = listOf(
            call("py", "python_sandbox"),
            ExecutionStep(
                id = "res_step_py", type = "tool_result", toolName = "python_sandbox", toolCallId = "py", timestamp = 2,
                content = """{"workspace_changes":{"added":["/x"]}}""",
            ),
        )
        assertThat(toolInvocations(other).single().workspaceChanges).isNull()
    }
}
