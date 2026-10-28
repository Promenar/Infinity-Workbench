package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.ui.chat.ApprovalPreview
import kotlinx.serialization.json.*
import org.junit.Test

class ExecToolArgumentsTest {
    private val schema = """{"type":"object","properties":{"background":{"type":"boolean"},"timeout_seconds":{"type":"integer"},"max_runtime_minutes":{"type":"integer"},"yield_after_seconds":{"type":"integer"},"newline":{"type":"boolean"},"eof":{"type":"boolean"}},"additionalProperties":false}"""
    @Test fun `六种标量字符串转换并保留原始审批摘要`() {
        val raw = Json.parseToJsonElement("""{"background":"True","timeout_seconds":"30","max_runtime_minutes":"120","yield_after_seconds":"0","newline":"FALSE","eof":"true"}""").jsonObject
        val normalized = ExecToolArguments.normalize(raw)
        assertThat(normalized.arguments).isEqualTo(Json.parseToJsonElement("""{"background":true,"timeout_seconds":30,"max_runtime_minutes":120,"yield_after_seconds":0,"newline":false,"eof":true}"""))
        assertThat(normalized.notes).hasSize(6)
        val tool = ProtocolTool(function = ProtocolToolFunction("shell_exec", "执行", schema), sourceId = "builtin", runtimeToolId = "shell_exec", risk = ToolRisk.PROCESS)
        val result = ToolInvocationIdentityFactory.fromPreparedToolCall(com.promenar.infinity.data.model.ToolCall("call", "shell_exec", raw.toString()), tool, true) as ToolInvocationIdentityResolution.Valid
        assertThat(result.arguments).isEqualTo(raw)
        assertThat(result.identity.argumentsDigest).isEqualTo((ToolArgumentsValidator().validate(raw.toString()) as ToolArgumentsValidation.Valid).sha256)
    }
    @Test fun `非法字段说明期望与实际类型且不暴露输入正文`() {
        val result = ToolSchemaValidator().validate(schema, buildJsonObject { put("background", "maybe") }) as ToolSchemaValidation.Invalid
        assertThat(result.error.path).isEqualTo("$.background")
        assertThat(result.error.message).contains("布尔值")
        assertThat(result.error.message).contains("字符串")
        val invalid = runCatching { ExecToolArguments.integer(buildJsonObject { put("yield_after_seconds", 31) }, "yield_after_seconds", 15, 0..30) }.exceptionOrNull()
        assertThat(invalid?.message).contains("0–30")
        assertThat(invalid?.message).contains("数字")
    }
    @Test fun `任务输入逐次审批且审批卡展示完整文本`() {
        val args = """{"job_id":"job-a","text":"yes\n下一行","newline":false,"eof":true}"""
        val tool = ProtocolTool(function = ProtocolToolFunction("job_input", "输入", "{}"), sourceId = "builtin", runtimeToolId = "shell_exec", risk = ToolRisk.PROCESS)
        val target = ToolExecutionTargets.extract(tool, args)
        assertThat(target).contains("job-a")
        assertThat(ToolExecutionTargets.canRemember(target!!)).isFalse()
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "job_input", ToolRisk.PROCESS)).isTrue()
        assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "job_input", ToolRisk.PROCESS)).isFalse()
        assertThat(ApprovalPreview.format("job_input", Json.parseToJsonElement(args).jsonObject)?.detail).isEqualTo("yes\n下一行")
    }
}
