package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.state.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class StateBoardPresentationTest {
    @Test fun `值展示支持文字单位列表与进度且进度限制在有效范围`() {
        assertThat(stateValueText(JsonPrimitive(65), "点")).isEqualTo("65 点")
        assertThat(stateValueText(JsonArray(listOf(JsonPrimitive("火把"), JsonPrimitive("绳子"))))).isEqualTo("火把、绳子")
        val field = StateField("hp", "生命值", "meter", Json.parseToJsonElement("""{"current":65,"max":100}"""))
        assertThat(stateValueText(field.value)).isEqualTo("65/100")
        assertThat(field.progress()).isWithin(0.001f).of(0.65f)
    }
    @Test fun `编辑转换为有类型的操作而不把数值存成字符串`() {
        assertThat(stateFieldEdit(StateField("hp", "生命值", "number"), "65", "", emptyList()).value).isEqualTo(JsonPrimitive(65))
        assertThat(stateFieldEdit(StateField("bag", "背包", "list"), "", "", listOf("火把")).value).isEqualTo(JsonArray(listOf(JsonPrimitive("火把"))))
        assertThrows(IllegalArgumentException::class.java) { stateFieldEdit(StateField("hp", "生命值", "meter"), "101", "100", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { stateFieldEdit(StateField("hp", "生命值", "number"), "\"65\"", "", emptyList()) }
    }
    private fun invocation(name: String, content: String, failed: Boolean = false) = ToolInvocationPresentation(listOf(
        ExecutionStep(id = "call", type = "tool_call", toolName = name),
        ExecutionStep(id = "result", type = if (failed) "error" else "tool_result", toolName = name, content = content),
    ))
    @Test fun `时间线摘要显示前后值及列表变化`() {
        val summary = stateToolSummary(invocation("state_update", """{"changes":[{"key":"hp","label":"生命值","before":80,"after":65},{"key":"bag","label":"背包","before":[],"after":["火把"]}]}"""))!!
        assertThat(summary.resource).isEqualTo(R.string.state_updated)
        assertThat(summary.detail).isEqualTo("生命值 80→65，背包 +火把")
    }
    @Test fun `定义读取移除与错误摘要不伪报成功`() {
        assertThat(stateToolSummary(invocation("state_define", """{"created":true,"board":{"title":"冒险"}}"""))!!.resource).isEqualTo(R.string.state_created)
        assertThat(stateToolSummary(invocation("state_get", "{}"))!!.resource).isEqualTo(R.string.state_read)
        assertThat(stateToolSummary(invocation("state_remove", "{}"))!!.resource).isEqualTo(R.string.state_removed)
        assertThat(stateToolSummary(invocation("state_update", "bad"))).isNull()
        assertThat(stateToolSummary(invocation("state_update", "{}", failed = true))).isNull()
    }
}
