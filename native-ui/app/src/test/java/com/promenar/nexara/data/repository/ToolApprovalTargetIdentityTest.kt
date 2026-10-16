package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ApprovalCallIdentity
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.json
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class ToolApprovalTargetIdentityTest {
    @Test
    fun `审批持久化包含完整目标且篡改目标使精确身份失效`() {
        val target = """["path","/docs/a.md"]"""
        val arguments = """{"path":"/docs/a.md"}"""
        val digest = (ToolArgumentsValidator().validate(arguments) as ToolArgumentsValidation.Valid).sha256
        val request = ToolApprovalRequestFactory.create(
            "m",
            listOf(ApprovalCallCandidate(
                call = ToolCall("c", "write_file", arguments),
                risk = ToolRisk.FILE_WRITE,
                identity = ToolInvocationIdentity("builtin:write", "write_file", digest, "def-digest", true),
                executionTarget = target,
            )),
        )

        val restored = json.decodeFromString<ApprovalRequest>(json.encodeToString(request))

        assertThat(restored.calls.single().executionTarget).isEqualTo(target)
        assertThat(ToolApprovalRequestFactory.isValid(restored)).isTrue()
        assertThat(ToolApprovalRequestFactory.isValid(restored.copy(
            calls = listOf(restored.calls.single().copy(executionTarget = """["path","/docs/b.md"]""")),
        ))).isFalse()
        assertThat(ToolApprovalRequestFactory.isValid(restored.copy(
            calls = listOf(restored.calls.single().copy(executionTarget = null)),
        ))).isFalse()
    }

    @Test
    fun `没有目标的历史审批保持原 identity hash 兼容`() {
        val request = ToolApprovalRequestFactory.createFromIdentities("m", listOf(legacyCall()))

        assertThat(request.identityHash).isEqualTo("37378cc90ca80d80b5eae42c43885146d7734097d09c5a5a2fb3b1bc641295fd")
        assertThat(ToolApprovalRequestFactory.isValid(request)).isTrue()
    }

    @Test
    fun `目标边界保留元组身份且不能跨调用移动授权目标`() {
        val first = legacyCall().copy(executionTarget = "a\u0000b")
        val second = legacyCall().copy(toolCallId = "c2", executionTarget = "c")
        val request = ToolApprovalRequestFactory.createFromIdentities("m", listOf(first, second))
        val moved = ToolApprovalRequestFactory.createFromIdentities("m", listOf(
            first.copy(executionTarget = "a"),
            second.copy(executionTarget = "b\u0000c"),
        ))

        assertThat(request.identityHash).isNotEqualTo(moved.identityHash)
    }

    private fun legacyCall() = ApprovalCallIdentity(
        toolCallId = "c",
        runtimeToolId = "builtin:write",
        toolName = "write_file",
        argumentsDigest = "args-digest",
        definitionDigest = "def-digest",
        requiresApproval = true,
        argumentsSummary = "{}",
        risk = "file_write",
    )
}
