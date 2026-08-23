package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ToolArgumentsValidatorTest {
    private val validator = ToolArgumentsValidator()

    @Test
    fun `嵌套参数保持 JSON 类型并递归规范化对象键顺序`() {
        val result = validator.validate(
            """{"z":1,"array":[3,null,{"x":"y"}],"a":{"b":2,"a":true}}""",
        )

        assertThat(result).isInstanceOf(ToolArgumentsValidation.Valid::class.java)
        val valid = result as ToolArgumentsValidation.Valid
        assertThat(valid.canonicalJson)
            .isEqualTo("""{"a":{"a":true,"b":2},"array":[3,null,{"x":"y"}],"z":1}""")
        assertThat(valid.sha256)
            .isEqualTo("2ed8fa4a89c182375412ed204267e54ae6c3f35ea8023d4523235fc619c5e653")
        assertThat(valid.arguments.getValue("z").jsonPrimitive.int).isEqualTo(1)
        assertThat(valid.arguments.getValue("a").jsonObject.getValue("a").jsonPrimitive.boolean).isTrue()
        assertThat(valid.arguments.getValue("array").jsonArray[1]).isEqualTo(JsonNull)
    }

    @Test
    fun `非对象与畸形 JSON 返回稳定错误且不抛出解析异常`() {
        val nonObject = validator.validate("[1,2]") as ToolArgumentsValidation.Invalid
        val malformed = validator.validate("{\"a\":") as ToolArgumentsValidation.Invalid

        assertThat(nonObject.error.code).isEqualTo(ToolArgumentsErrorCode.ROOT_NOT_OBJECT)
        assertThat(malformed.error.code).isEqualTo(ToolArgumentsErrorCode.MALFORMED_JSON)
        assertThat(nonObject.error.message).isEqualTo("工具参数根节点必须是 JSON object")
        assertThat(malformed.error.message).isEqualTo("工具参数不是合法 JSON")
    }
}
