package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ApprovalCallIdentity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ApprovalPreviewTest {
    private fun digest(arguments: String) =
        (ToolArgumentsValidator().validate(arguments) as ToolArgumentsValidation.Valid).sha256

    private fun call(name: String, arguments: String, digest: String = digest(arguments)) = ApprovalCallIdentity(
        toolCallId = "c1",
        runtimeToolId = name,
        toolName = name,
        argumentsDigest = digest,
        definitionDigest = "d",
        requiresApproval = true,
        argumentsSummary = arguments.take(20),
        risk = "FILE_WRITE",
    )

    private fun messages(name: String, arguments: String) = listOf(
        Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", name, arguments))),
    )

    @Test
    fun `write preview shows target and content excerpt from digest matched arguments`() {
        val arguments = """{"path":"/notes/a.md","content":"line1\nline2","expectedHash":"h"}"""

        val preview = ApprovalPreview.forCall(call("write_file", arguments), messages("write_file", arguments), "a1")

        assertThat(preview).isEqualTo(
            ApprovalPreview.Preview(ApprovalPreview.Action.OVERWRITE, "/notes/a.md", "line1\nline2"),
        )
    }

    @Test
    fun `digest mismatch falls back to summary`() {
        val arguments = """{"path":"/a.md","content":"x","expectedHash":"h"}"""
        val approved = call("write_file", arguments, digest = "other")

        assertThat(ApprovalPreview.forCall(approved, messages("write_file", arguments), "a1")).isNull()
    }

    @Test
    fun `patch preview lists operations with line ranges`() {
        val arguments = """{"path":"/a.md","expectedHash":"h","operations":[{"action":"replace_lines","startLine":3,"endLine":4,"newContent":"new"},{"action":"delete_lines","startLine":9,"endLine":9}]}"""

        val preview = ApprovalPreview.forCall(call("patch_file", arguments), messages("patch_file", arguments), "a1")!!

        assertThat(preview.action).isEqualTo(ApprovalPreview.Action.EDIT)
        assertThat(preview.detail).isEqualTo("~ L3–4\n  + new\n- L9–9")
    }

    @Test
    fun `long content keeps every line without truncation marker`() {
        val body = (1..20).joinToString("\\n") { "l$it" }
        val arguments = """{"name":"b.md","parentPath":"/docs","content":"$body"}"""

        val preview = ApprovalPreview.forCall(call("create_file", arguments), messages("create_file", arguments), "a1")!!

        assertThat(preview.target).isEqualTo("/docs/b.md")
        assertThat(preview.detail).isEqualTo((1..20).joinToString("\n") { "l$it" })
        assertThat(preview.detail).doesNotContain("… (+")
    }

    @Test
    fun `python exec preview keeps full 80 line code and long lines`() {
        val longLine = "x = '" + "a".repeat(300) + "'"
        val code = listOf("import os", longLine) + (3..80).map { "print($it)" }
        val arguments = buildJsonObject { put("code", code.joinToString("\n")) }.toString()

        val preview = ApprovalPreview.forCall(call("python_exec", arguments), messages("python_exec", arguments), "a1")!!

        assertThat(preview.action).isEqualTo(ApprovalPreview.Action.SCRIPT)
        assertThat(preview.detail!!.lines()).containsExactlyElementsIn(code).inOrder()
        assertThat(preview.detail).doesNotContain("… (+")
    }

    @Test
    fun `sandbox script with network keeps marker and full code`() {
        val code = (1..30).joinToString("\n") { "console.log($it)" }
        val arguments = buildJsonObject { put("code", code); put("allow_network", true) }.toString()

        val preview = ApprovalPreview.forCall(call("exec_js", arguments), messages("exec_js", arguments), "a1")!!

        assertThat(preview.scriptAllowsNetwork).isTrue()
        assertThat(preview.detail).isEqualTo(ApprovalPreview.NETWORK_ALLOWED + "\n" + code)
    }

    @Test
    fun `patch preview lists every operation and full new content`() {
        val operations = buildJsonArray {
            (1..15).forEach { index ->
                add(buildJsonObject {
                    put("action", "replace_lines"); put("startLine", index); put("endLine", index)
                    put("newContent", "a$index\nb$index")
                })
            }
        }
        val arguments = buildJsonObject {
            put("path", "/a.md"); put("expectedHash", "h"); put("operations", operations)
        }.toString()

        val preview = ApprovalPreview.forCall(call("patch_file", arguments), messages("patch_file", arguments), "a1")!!

        val expected = (1..15).joinToString("\n") { "~ L$it–$it\n  + a$it\n  + b$it" }
        assertThat(preview.detail).isEqualTo(expected)
    }

    @Test
    fun `unknown tools have no preview`() {
        val arguments = """{"q":"x"}"""

        assertThat(ApprovalPreview.forCall(call("web_search", arguments), messages("web_search", arguments), "a1")).isNull()
    }

    @Test
    fun `termux command preview shows the command text and does not claim the workspace`() {
        val preview = ApprovalPreview.format(
            "termux_exec",
            buildJsonObject { put("command", "for i in 1 2; do echo tick; done"); put("background", true) },
        )

        assertThat(preview).isEqualTo(
            ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", "for i in 1 2; do echo tick; done"),
        )
    }
}
