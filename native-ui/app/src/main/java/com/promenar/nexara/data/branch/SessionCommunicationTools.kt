package com.promenar.nexara.data.branch

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.*
import com.promenar.nexara.ui.chat.manager.registry.*
import com.promenar.nexara.ui.chat.manager.skills.SessionAddressHandler
import kotlinx.serialization.json.JsonObject

class BranchSessionAddressHandler(private val service: () -> BranchSessionService) : SessionAddressHandler {
    override fun accepts(id: String) = !id.startsWith("sa_")
    override suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext) =
        service().address(operation, id, args, context)
}

class SessionListTool(private val service: () -> BranchSessionService) : SkillDefinition {
    override val id = "session_list"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description = "列出当前会话家族。scope=assistant 每次需用户批准，只列同一助手会话；其它助手不可见。分支与子代理只访问自己的主会话。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"properties":{"scope":{"type":"string","enum":["family","assistant"],"default":"family"}}}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        require(ToolSchemaValidator().validate(parametersSchema, args) !is ToolSchemaValidation.Invalid) { "session_list 参数不合法" }
        ToolResult(id, service().list(args, context), "success")
    } catch (failure: IllegalArgumentException) { ToolResult(id, failure.message ?: "无法读取会话列表", "error") }
}
