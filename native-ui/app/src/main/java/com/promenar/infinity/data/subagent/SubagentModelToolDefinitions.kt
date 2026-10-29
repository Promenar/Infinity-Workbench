package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.ToolDefinitionDigestResolution
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import kotlinx.serialization.json.*

/** 会话广告与执行前重验共用的模型参数说明；底层定义仍独立参与指纹校验。 */
object SubagentModelToolDefinitions {
    fun forSession(
        tool: ProtocolTool,
        policy: AgentSubagentModelPolicy,
        modelName: (String) -> String? = { null },
    ): ProtocolTool {
        if (!isAgentTask(tool)) return tool
        val description = if (!policy.allowPerTaskModel) {
            "Model selection is disabled for this agent. Do not pass model; omit it to use the default subagent model."
        } else {
            val choices = policy.selectableModelIds.distinct().joinToString(", ") { id ->
                val name = modelName(id)?.takeIf { it.isNotBlank() && it != id }
                if (name == null) id else "$id ($name)"
            }.ifEmpty { "(none)" }
            "Optional model ID from this agent's allowed list: $choices. Pass the ID, not the display name. Omit model to use the default subagent model."
        }
        return rewrite(tool, description)
    }

    /** 策略已由解析器在当前会话中读取；只复用参数说明，底层摘要从当前 Skill 重新计算。 */
    fun forExecution(tool: ProtocolTool, sessionDefinition: ProtocolTool?): ProtocolTool {
        if (!isAgentTask(tool) || sessionDefinition == null || !isAgentTask(sessionDefinition)) return tool
        val description = modelProperty(Json.parseToJsonElement(sessionDefinition.function.parameters).jsonObject)
            ?.get("description")?.jsonPrimitive?.content ?: return tool
        return rewrite(tool, description)
    }

    private fun rewrite(tool: ProtocolTool, description: String): ProtocolTool {
        val base = tool.baseDefinitionDigest
            ?: (ToolInvocationIdentityFactory.definitionDigest(tool) as? ToolDefinitionDigestResolution.Valid)?.digest
            ?: return tool
        val schema = Json.parseToJsonElement(tool.function.parameters).jsonObject
        val properties = schema["properties"]?.jsonObject ?: return tool
        val tasks = properties["tasks"]?.jsonObject ?: return tool
        val items = tasks["items"]?.jsonObject ?: return tool
        val taskProperties = items["properties"]?.jsonObject ?: return tool
        val model = taskProperties["model"]?.jsonObject ?: return tool
        val updatedModel = JsonObject(model + ("description" to JsonPrimitive(description)))
        val updatedItems = JsonObject(items + ("properties" to JsonObject(taskProperties + ("model" to updatedModel))))
        val updatedTasks = JsonObject(tasks + ("items" to updatedItems))
        val updated = JsonObject(schema + ("properties" to JsonObject(properties + ("tasks" to updatedTasks))))
        return tool.copy(function = tool.function.copy(parameters = updated.toString()), baseDefinitionDigest = base)
    }

    private fun modelProperty(schema: JsonObject): JsonObject? = schema["properties"]?.jsonObject
        ?.get("tasks")?.jsonObject?.get("items")?.jsonObject?.get("properties")?.jsonObject?.get("model")?.jsonObject

    private fun isAgentTask(tool: ProtocolTool): Boolean = tool.sourceId == "builtin" && tool.function.name == SubagentLimits.TOOL_NAME
}
