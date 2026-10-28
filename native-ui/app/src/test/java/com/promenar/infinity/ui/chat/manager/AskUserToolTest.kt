package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.ask.AskUserLimits
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import kotlinx.serialization.json.*
import org.junit.Test

class AskUserToolTest {
    @Test fun `参数结构符合现有工具 schema 支持范围`() {
        assertThat(ToolSchemaValidator().validateDefinition(AskUserTool().parametersSchema))
            .isInstanceOf(ToolSchemaValidation.Valid::class.java)
    }

    @Test fun `省略可选字段使用默认值且支持码点标题`() {
        val result = AskUserProtocol.decode(valid.replace("方案", "😀".repeat(AskUserLimits.MAX_HEADER_CHARS))) as AskUserDecode.Valid
        assertThat(result.request.questions.single().multiSelect).isFalse()
        assertThat(result.request.questions.single().options.first().description).isEmpty()
    }

    @Test fun `参数解码各类非法字段提供具体路径且不回显原文`() {
        val cases = listOf(
            "{" to "合法 JSON", "[]" to "根节点", "{}" to "questions",
            """{"questions":{}}""" to "questions",
            """{"questions":[null]}""" to "questions[0]",
            valid.replace("\"header\":\"方案\"", "\"header\":42") to "header",
            valid.replace("\"question\":\"选择方案\"", "\"question\":null") to "question",
            valid.replace("\"header\"", "\"multiSelect\":\"false\",\"header\"") to "multiSelect",
            valid.replace("\"label\":\"甲\"", "\"label\":null") to "label",
            valid.replace("\"label\":\"甲\"", "\"label\":\"甲\",\"description\":4") to "description",
            valid.replace("\"label\":\"甲\"", "\"label\":\"甲\",\"description\":\"\"") to "description",
            valid.replace("\"label\":\"甲\"", "\"label\":\"甲\",\"description\":\" \"") to "description",
        )
        cases.forEach { (input, path) ->
            val result = AskUserProtocol.decode(input) as AskUserDecode.Invalid
            assertThat(result.message).contains(path)
        }
        assertThat((AskUserProtocol.decode("{sensitive-input") as AskUserDecode.Invalid).message)
            .doesNotContain("sensitive-input")
    }

    @Test fun `失败结果符合工具错误结构`() {
        val error = Json.parseToJsonElement(AskUserProtocol.unavailable()).jsonObject
        assertThat(error.keys).containsExactly("status", "error", "message", "suggestion")
        assertThat(error.getValue("status").jsonPrimitive.content).isEqualTo("error")
        assertThat(error.getValue("suggestion").jsonPrimitive.content).contains("合理默认")
    }

    private val valid = """{"questions":[{"header":"方案","question":"选择方案","options":[{"label":"甲"},{"label":"乙"}]}]}"""
}
