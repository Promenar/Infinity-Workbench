package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ApprovalCallIdentity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
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
    fun `long content is truncated with remaining line count`() {
        val body = (1..20).joinToString("\\n") { "l$it" }
        val arguments = """{"name":"b.md","parentPath":"/docs","content":"$body"}"""

        val preview = ApprovalPreview.forCall(call("create_file", arguments), messages("create_file", arguments), "a1")!!

        assertThat(preview.target).isEqualTo("/docs/b.md")
        assertThat(preview.detail).endsWith("… (+8)")
    }

    @Test
    fun `unknown tools have no preview`() {
        val arguments = """{"q":"x"}"""

        assertThat(ApprovalPreview.forCall(call("web_search", arguments), messages("web_search", arguments), "a1")).isNull()
    }
}
