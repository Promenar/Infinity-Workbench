package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.git.remote.GitCredentialHost
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.secret.SecretIoFailure
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.domain.secret.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*

/** 持久化与暂停由生成运行时独占，普通执行入口不能创建输入请求。 */
class RequestSecretSkill : SkillDefinition {
    override val id = SecretIoLimits.REQUEST_TOOL_NAME
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "需要用户提供密码、令牌、API Key 或私钥口令时，使用保密输入卡，值不会交回模型。" +
        "purpose 说明用途；destination 指定已配置的凭据或本会话任务。session_env 允许命令读取，只做逻辑隔离，优先选凭据去向。" +
        "必须单独调用；同批其它工具不执行，多个请求只受理第一个；同批存在 ask_user 时由 ask_user 批次处理。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"required":["purpose","destination"],"properties":{
        "purpose":{"type":"string","description":"用途，1至200个字符"},
        "kind":{"type":"string","enum":["password","token","api_key","passphrase","other"]},
        "destination":{"type":"object","additionalProperties":false,"required":["type"],"properties":{
            "type":{"type":"string","enum":["git_credential","ssh_host","provider_api_key","job_input","session_env"]},
            "host":{"type":"string"},"username":{"type":"string"},"hostId":{"type":"string"},
            "field":{"type":"string","enum":["password","key_passphrase"]},"providerId":{"type":"string"},
            "jobId":{"type":"string"},"name":{"type":"string"}
        }}
    }}""".trimIndent()
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
        ToolResult(id, SecretRequestProtocol.failure("SECRET_INPUT_UNAVAILABLE"), "error")
}

class ShowSecretSkill(private val vault: SessionSecretVault) : SkillDefinition {
    override val id = SecretIoLimits.SHOW_TOOL_NAME
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "把本会话保密库槽位交给用户查看、复制、另存或销毁。只返回状态，值不进入工具结果；不挂起会话。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"required":["name","purpose"],"properties":{"name":{"type":"string"},"purpose":{"type":"string","description":"用途，1至200个字符"}}}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (ToolSchemaValidator().validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return failed("SECRET_INVALID_ARGUMENTS")
        val name = args.getValue("name").jsonPrimitive.content
        val purpose = args.getValue("purpose").jsonPrimitive.content
        if (!SecretIoLimits.SLOT_NAME.matches(name) || !SecretRequestProtocol.validPurpose(purpose)) return failed("SECRET_INVALID_ARGUMENTS")
        val assistant = context.assistantMessageId ?: return failed("SECRET_OUTPUT_UNAVAILABLE")
        val call = context.toolCallId ?: return failed("SECRET_OUTPUT_UNAVAILABLE")
        return try {
            vault.show(context.sessionId, assistant, call, name, purpose)
            ToolResult(id, buildJsonObject { put("status", "shown"); put("name", name) }.toString(), "success")
        } catch (_: IllegalArgumentException) { failed("SECRET_SLOT_NOT_FOUND")
        } catch (_: IllegalStateException) { failed("SECRET_SLOT_NOT_FOUND") }
    }
    private fun failed(code: String) = ToolResult(id, SecretRequestProtocol.failure(code), "error")
}

internal object SecretRequestProtocol {
    fun validPurpose(value: String) = value.isNotBlank() && value.length <= SecretIoLimits.MAX_PURPOSE_CHARS

    fun decode(arguments: String): SecretInputRequest = try {
        val raw = Json.decodeFromString<SecretInputRequest>(arguments)
        if (!validPurpose(raw.purpose)) throw SecretIoFailure("SECRET_INVALID_ARGUMENTS")
        val destination = when (val d = raw.destination) {
            is SecretDestination.GitCredential -> d.copy(host = GitCredentialHost.normalize(d.host)
                ?: throw SecretIoFailure("SECRET_INVALID_GIT_HOST")).also {
                if (it.username != null && (it.username.isBlank() || it.username.length > 256 || it.username.any { c -> c.isWhitespace() || c.isISOControl() || c == ':' }))
                    throw SecretIoFailure("SECRET_INVALID_USERNAME")
            }
            is SecretDestination.SessionEnv -> d.also { if (!SecretIoLimits.SLOT_NAME.matches(it.name)) throw SecretIoFailure("SECRET_INVALID_SLOT_NAME") }
            is SecretDestination.SshHost -> d.also { if (it.hostId.isBlank()) throw SecretIoFailure("SECRET_INVALID_ARGUMENTS") }
            is SecretDestination.ProviderApiKey -> d.also { if (it.providerId.isBlank()) throw SecretIoFailure("SECRET_INVALID_ARGUMENTS") }
            is SecretDestination.JobInput -> d.also { if (it.jobId.isBlank()) throw SecretIoFailure("SECRET_INVALID_ARGUMENTS") }
        }
        raw.copy(destination = destination)
    } catch (failure: SecretIoFailure) { throw failure
    } catch (_: Exception) { throw SecretIoFailure("SECRET_INVALID_ARGUMENTS") }

    fun failure(code: String) = buildJsonObject {
        put("status", "error"); put("error_code", code)
        put("message", "保密信息请求未执行，请检查参数或去向后重试。")
    }.toString()
}
