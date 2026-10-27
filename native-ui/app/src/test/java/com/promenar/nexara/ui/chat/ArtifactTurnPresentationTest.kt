package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.artifact.ArtifactKind
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.FileChangeSummary
import com.promenar.nexara.data.model.FileChangeType
import com.promenar.nexara.domain.media.MediaKind
import com.promenar.nexara.domain.media.MediaRef
import org.junit.Test

class ArtifactTurnPresentationTest {
    @Test fun `只从成功的artifact_publish结果取出产物身份`() {
        val result = """{"artifactId":"f1","title":"需求说明.md","kind":"document","version":2}"""
        assertThat(artifactInvocation(invocation("artifact_publish", result)))
            .isEqualTo(ArtifactInvocation("f1", "需求说明.md", ArtifactKind.DOCUMENT, 2))
        assertThat(artifactInvocation(invocation("artifact_list", result))).isNull()
        assertThat(artifactInvocation(invocation("artifact_publish", result, failed = true))).isNull()
        for (bad in listOf("broken", "{}", """{"artifactId":"f1","kind":"document","version":0}""", """{"artifactId":"f1","kind":"video","version":1}""")) {
            assertThat(artifactInvocation(invocation("artifact_publish", bad))).isNull()
        }
    }

    @Test fun `命令对账、文件工具与生成媒体都算工作区改动，删除与只读引用不算`() {
        val steps = listOf(
            ExecutionStep(id = "c1", type = "tool_call", toolName = "shell_exec", timestamp = 10),
            ExecutionStep(id = "r1", type = "tool_result", timestamp = 11, content = """
                {"exit_code":0,"workspace_changes":{"added":["/home/session/out/a.png"],"modified":["/home/session/b.md"],
                "removed":["/home/session/gone.txt"],"moved":[{"from":"/home/session/x.csv","to":"/home/session/y.csv"}]}}
            """.trimIndent()),
            ExecutionStep(id = "c2", type = "tool_call", toolName = "write_file", timestamp = 20),
            ExecutionStep(id = "r2", type = "tool_result", timestamp = 21, content = "ok", fileChanges = listOf(
                FileChangeSummary("/report.md", FileChangeType.CREATED),
                FileChangeSummary("/old.md", FileChangeType.DELETED),
            )),
            ExecutionStep(id = "c3", type = "tool_call", toolName = "image_generation", timestamp = 30),
            ExecutionStep(id = "r3", type = "tool_result", timestamp = 31, content = "{}", media = listOf(media("/media/gen-1.png"))),
            ExecutionStep(id = "c4", type = "tool_call", toolName = "view_media", timestamp = 40),
            ExecutionStep(id = "r4", type = "tool_result", timestamp = 41, content = "{}", media = listOf(media("/photos/existing.jpg"))),
        )
        val changes = workspaceChangedFiles("m1", steps)
        assertThat(changes.map { it.path }).containsExactly(
            "/home/session/out/a.png", "/home/session/b.md", "/home/session/y.csv", "/report.md", "/media/gen-1.png",
        ).inOrder()
        assertThat(changes.map { it.messageId }.toSet()).containsExactly("m1")
        assertThat(changes.first().changedAt).isEqualTo(11)
    }

    @Test fun `失败的调用不产生改动`() {
        val steps = listOf(
            ExecutionStep(id = "c1", type = "tool_call", toolName = "shell_exec"),
            ExecutionStep(id = "r1", type = "error", content = """{"workspace_changes":{"added":["/a.txt"]}}"""),
        )
        assertThat(workspaceChangedFiles("m1", steps)).isEmpty()
    }

    private fun media(path: String) = MediaRef(path, "image/png", MediaKind.IMAGE, 10)

    private fun invocation(name: String, content: String, failed: Boolean = false) = ToolInvocationPresentation(listOf(
        ExecutionStep(id = "call", type = "tool_call", toolName = name),
        ExecutionStep(id = "result", type = if (failed) "error" else "tool_result", content = content),
    ))
}
