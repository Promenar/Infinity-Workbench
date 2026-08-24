package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ToolSchemaValidatorTest {
    private val validator = ToolSchemaValidator()

    @Test
    fun `接受支持关键字并递归验证嵌套对象数组枚举与额外字段策略`() {
        val schema = """{
            "additionalProperties":false,
            "required":["operations"],
            "properties":{
              "operations":{
                "type":"array",
                "items":{
                  "type":"object",
                  "additionalProperties":false,
                  "required":["action"],
                  "properties":{
                    "action":{"enum":["add","remove"],"type":"string"},
                    "count":{"type":"integer"},
                    "payload":{"type":["object","null"],"additionalProperties":true}
                  }
                }
              }
            },
            "type":"object"
        }""".trimIndent()
        val arguments = Json.parseToJsonElement(
            """{"operations":[{"action":"add","count":2,"payload":{"nested":[true,null,1.5]}}]}""",
        ).jsonObject

        val result = validator.validate(schema, arguments)

        assertThat(result).isInstanceOf(ToolSchemaValidation.Valid::class.java)
        val valid = result as ToolSchemaValidation.Valid
        assertThat(valid.canonicalSchema).isEqualTo(
            """{"additionalProperties":false,"properties":{"operations":{"items":{"additionalProperties":false,"properties":{"action":{"enum":["add","remove"],"type":"string"},"count":{"type":"integer"},"payload":{"additionalProperties":true,"type":["object","null"]}},"required":["action"],"type":"object"},"type":"array"}},"required":["operations"],"type":"object"}""",
        )
    }

    @Test
    fun `未知 schema 关键字稳定拒绝且不回显 schema 内容`() {
        val result = validator.validate(
            """{"type":"object","properties":{},"patternProperties":{"token":{"type":"string"}}}""",
            Json.parseToJsonElement("{}").jsonObject,
        ) as ToolSchemaValidation.Invalid

        assertThat(result.error.code).isEqualTo(ToolSchemaErrorCode.UNSUPPORTED_KEYWORD)
        assertThat(result.error.path).isEqualTo("$.patternProperties")
        assertThat(result.error.message).doesNotContain("token")
    }

    @Test
    fun `缺少必填项与额外字段分别返回稳定 code 和 JSON path`() {
        val schema = """{
          "type":"object",
          "required":["secret"],
          "properties":{"secret":{"type":"string"}},
          "additionalProperties":false
        }""".trimIndent()

        val missing = validator.validate(schema, Json.parseToJsonElement("{}").jsonObject)
            as ToolSchemaValidation.Invalid
        val extra = validator.validate(
            schema,
            Json.parseToJsonElement("""{"secret":"ok","unexpected":"private"}""").jsonObject,
        ) as ToolSchemaValidation.Invalid

        assertThat(missing.error.code).isEqualTo(ToolSchemaErrorCode.REQUIRED_PROPERTY_MISSING)
        assertThat(missing.error.path).isEqualTo("$.secret")
        assertThat(extra.error.code).isEqualTo(ToolSchemaErrorCode.ADDITIONAL_PROPERTY_NOT_ALLOWED)
        assertThat(extra.error.path).isEqualTo("$.unexpected")
        assertThat(extra.error.message).doesNotContain("private")
    }

    @Test
    fun `类型不符与枚举不符在嵌套数组中返回精确路径`() {
        val schema = """{
          "type":"object",
          "properties":{"items":{"type":"array","items":{"type":"object","properties":{"mode":{"type":"string","enum":["safe"]}},"required":["mode"]}}},
          "required":["items"]
        }""".trimIndent()

        val typeMismatch = validator.validate(
            schema,
            Json.parseToJsonElement("""{"items":[{"mode":1}]}""").jsonObject,
        ) as ToolSchemaValidation.Invalid
        val enumMismatch = validator.validate(
            schema,
            Json.parseToJsonElement("""{"items":[{"mode":"unsafe"}]}""").jsonObject,
        ) as ToolSchemaValidation.Invalid

        assertThat(typeMismatch.error.code).isEqualTo(ToolSchemaErrorCode.TYPE_MISMATCH)
        assertThat(typeMismatch.error.path).isEqualTo("$.items[0].mode")
        assertThat(enumMismatch.error.code).isEqualTo(ToolSchemaErrorCode.ENUM_MISMATCH)
        assertThat(enumMismatch.error.path).isEqualTo("$.items[0].mode")
        assertThat(enumMismatch.error.message).doesNotContain("unsafe")
    }

    @Test
    fun `无效 schema 本身 fail closed`() {
        val malformed = validator.validate("{", Json.parseToJsonElement("{}").jsonObject)
            as ToolSchemaValidation.Invalid
        val invalidRequired = validator.validate(
            """{"type":"object","required":"x","properties":{}}""",
            Json.parseToJsonElement("{}").jsonObject,
        ) as ToolSchemaValidation.Invalid

        assertThat(malformed.error.code).isEqualTo(ToolSchemaErrorCode.MALFORMED_SCHEMA)
        assertThat(malformed.error.path).isEqualTo("$")
        assertThat(invalidRequired.error.code).isEqualTo(ToolSchemaErrorCode.INVALID_SCHEMA)
        assertThat(invalidRequired.error.path).isEqualTo("$.required")
    }
}
