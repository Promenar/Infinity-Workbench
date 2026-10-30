package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.ask.AskUserLimits
import com.promenar.infinity.domain.ask.AskUserOption
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*

/** 提问由生成运行时持久化并暂停；普通技能执行入口不能创建待回答状态。 */
class AskUserTool : SkillDefinition {
    override val id = AskUserLimits.TOOL_NAME
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "执行中遇到只有用户能决定的方案取舍、范围或偏好时，集中提出 1–4 个选择题。" +
        "能从上下文或合理默认推断的事不要问。推荐项放在第一项，label 末尾标注“（推荐）”。" +
        "用户总能自由回答或跳过，不要添加“其他”选项。相关问题一次问完。" +
        "不要用它索取密码、令牌或密钥，请调用 request_secret。" +
        "ask_user 必须单独调用；同批其它调用全部不会执行，多个 ask_user 只受理第一个。回答后按需重新调用其它工具。"
    override val parametersSchema = """{
        "type":"object","additionalProperties":false,"required":["questions"],"properties":{
          "questions":{"type":"array","description":"包含 1 至 ${AskUserLimits.MAX_QUESTIONS} 个问题，header 互不重复。","items":{
            "type":"object","additionalProperties":false,"required":["header","question","options"],"properties":{
              "header":{"type":"string","description":"非空白短名，最多 ${AskUserLimits.MAX_HEADER_CHARS} 个 Unicode 字符。"},
              "question":{"type":"string","description":"非空白的完整问题。"},"multiSelect":{"type":"boolean","default":false},
              "options":{"type":"array","description":"包含 ${AskUserLimits.MIN_OPTIONS} 至 ${AskUserLimits.MAX_OPTIONS} 个选项，label 非空白且互不重复。","items":{
                "type":"object","additionalProperties":false,"required":["label"],"properties":{
                  "label":{"type":"string"},"description":{"type":"string"}
                }
              }}
            }
          }}
        }
    }""".trimIndent()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
        ToolResult(id = id, content = AskUserProtocol.unavailable(), status = "error")
}

internal sealed interface AskUserDecode {
    data class Valid(val request: AskUserRequest) : AskUserDecode
    data class Invalid(val message: String) : AskUserDecode
}

internal object AskUserProtocol {
    fun failure(error: String, message: String, suggestion: String = "请按工具定义修正后重试。") = buildJsonObject {
        put("status", "error")
        put("error", error)
        put("message", message)
        put("suggestion", suggestion)
    }.toString()

    fun unavailable() = failure("ASK_USER_UNAVAILABLE", "当前运行无法向用户提问。",
        "请按合理默认继续，并在回答中说明采用的假设。")

    fun decode(arguments: String): AskUserDecode = try {
        val root = Json.parseToJsonElement(arguments) as? JsonObject
            ?: throw IllegalArgumentException("参数根节点必须是 JSON object")
        fun JsonObject.fields(allowed: Set<String>, path: String) {
            require(keys.all { it in allowed }) { "$path 含未声明的字段" }
        }
        fun JsonObject.text(key: String, path: String, optional: Boolean = false): String {
            val value = get(key) ?: if (optional) return "" else throw IllegalArgumentException("$path.$key 为必填字符串")
            require(value is JsonPrimitive && value.isString) { "$path.$key 必须是字符串" }
            require(!optional || value.content.isNotBlank()) { "$path.$key 不得为空白" }
            return value.content
        }
        root.fields(setOf("questions"), "参数")
        val questions = root["questions"] as? JsonArray ?: throw IllegalArgumentException("questions 必须是数组")
        val request = AskUserRequest(questions.mapIndexed { index, element ->
            val path = "questions[$index]"
            val question = element as? JsonObject ?: throw IllegalArgumentException("$path 必须是对象")
            question.fields(setOf("header", "question", "multiSelect", "options"), path)
            val multi = question["multiSelect"]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$path.multiSelect 必须是布尔值" }
                it.boolean
            } ?: false
            val options = question["options"] as? JsonArray ?: throw IllegalArgumentException("$path.options 必须是数组")
            AskUserQuestion(question.text("header", path), question.text("question", path), multi,
                options.mapIndexed { optionIndex, option ->
                    val optionPath = "$path.options[$optionIndex]"
                    val obj = option as? JsonObject ?: throw IllegalArgumentException("$optionPath 必须是对象")
                    obj.fields(setOf("label", "description"), optionPath)
                    AskUserOption(obj.text("label", optionPath), obj.text("description", optionPath, optional = true))
                })
        })
        AskUserValidation.request(request)?.let { AskUserDecode.Invalid(it) } ?: AskUserDecode.Valid(request)
    } catch (error: IllegalArgumentException) {
        // JSON 解析异常可能包含参数原文，只返回校验器自己生成的字段路径。
        AskUserDecode.Invalid(if (error is kotlinx.serialization.SerializationException) "参数必须是合法 JSON" else error.message ?: "参数不合法")
    }
}
