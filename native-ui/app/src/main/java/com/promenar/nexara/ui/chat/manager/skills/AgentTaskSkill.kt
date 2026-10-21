package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.subagent.SubagentLauncher
import com.promenar.nexara.data.subagent.SubagentLimits
import com.promenar.nexara.data.subagent.SubagentModelUnavailable
import com.promenar.nexara.data.subagent.SubagentPreset
import com.promenar.nexara.data.subagent.SubagentRun
import com.promenar.nexara.data.subagent.SubagentSpec
import com.promenar.nexara.data.subagent.SubagentStatus
import com.promenar.nexara.data.subagent.SubagentToolProfile
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 派生子代理：每个子代理在独立上下文中完成一项子任务，最终正文作为本工具结果交回。 */
class AgentTaskSkill(private val launcher: () -> SubagentLauncher) : SkillDefinition {
    override val id = SubagentLimits.TOOL_NAME
    override val name = SubagentLimits.TOOL_NAME
    override val description = "Delegate self-contained subtasks to subagents. Each subagent works in a fresh context with this session's " +
        "model, workspace and approval rules, cannot ask the user anything, and returns only its final answer. List up to " +
        "${SubagentLimits.MAX_PARALLEL} independent tasks in one call to run them in parallel. Write each prompt as a complete brief: " +
        "goal, relevant paths or facts, constraints and the expected answer format. Use preset \"explorer\" for read-only research."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "tasks":{
                "type":"array",
                "description":"1 to ${SubagentLimits.MAX_PARALLEL} independent tasks",
                "items":{
                    "type":"object",
                    "properties":{
                        "description":{"type":"string","description":"Short task title shown to the user, at most 60 characters"},
                        "prompt":{"type":"string","description":"Complete, self-contained instructions for the subagent"},
                        "preset":{"type":"string","enum":["explorer"],"description":"Optional preset; explorer is read-only"},
                        "tool_profile":{"type":"string","enum":["read_only","inherit"],"description":"read_only allows only read tools; inherit uses this session's tools. Defaults to inherit, or read_only for explorer"}
                    },
                    "required":["description","prompt"],
                    "additionalProperties":false
                }
            }
        },
        "required":["tasks"],
        "additionalProperties":false
    }""".trimIndent()

    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val specs = parse(args) ?: return failure(
            "Invalid agent_task arguments: supply tasks with 1 to ${SubagentLimits.MAX_PARALLEL} items, each with a non-empty description and prompt.",
        )
        val stepId = context.toolStepId ?: "agent_task_${System.currentTimeMillis()}"
        val runs = try {
            launcher().launch(context, stepId, specs)
        } catch (unavailable: SubagentModelUnavailable) {
            // 只回显启动器给出的固定原因；其它异常交给工具执行器统一脱敏。
            return failure(unavailable.message ?: "Subagents are unavailable in this session.")
        }
        return ToolResult(
            id = "agent_task_${System.currentTimeMillis()}",
            content = formatResults(runs),
            status = if (runs.all { it.status != SubagentStatus.COMPLETED }) "error" else "success",
        )
    }

    internal fun parse(args: JsonObject): List<SubagentSpec>? {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return null
        val tasks = args["tasks"] as? JsonArray ?: return null
        if (tasks.size !in 1..SubagentLimits.MAX_PARALLEL) return null
        return tasks.map { element ->
            val task = element as? JsonObject ?: return null
            fun text(key: String) = (task[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            val description = text("description")?.takeIf(String::isNotEmpty) ?: return null
            val prompt = text("prompt")?.takeIf(String::isNotEmpty) ?: return null
            val preset = text("preset")?.let { SubagentPreset.parse(it) ?: return null }
            val profile = text("tool_profile")?.let { SubagentToolProfile.parse(it) ?: return null }
            SubagentSpec(
                description = description.take(MAX_DESCRIPTION_CHARS),
                prompt = prompt.take(SubagentLimits.MAX_PROMPT_CHARS),
                preset = preset,
                // 预置子代理的工具档位只能收窄，不能被参数放宽。
                toolProfile = when {
                    preset?.profile == SubagentToolProfile.READ_ONLY -> SubagentToolProfile.READ_ONLY
                    else -> profile ?: SubagentToolProfile.INHERIT
                },
            )
        }
    }

    private fun failure(message: String) = ToolResult(
        id = "agent_task_error_${System.currentTimeMillis()}",
        content = message,
        status = "error",
    )

    companion object {
        internal const val MAX_DESCRIPTION_CHARS = 60

        /** 交回主 Agent 的结果：每个子代理一节，总长度按子代理数平分上限。 */
        internal fun formatResults(runs: List<SubagentRun>): String {
            val budget = SubagentLimits.MAX_RESULT_CHARS / runs.size.coerceAtLeast(1)
            return runs.joinToString("\n\n") { run ->
                val title = "## Subagent ${run.index + 1}: ${run.description}"
                val body = when (run.status) {
                    SubagentStatus.COMPLETED -> run.result.orEmpty().let {
                        if (it.length <= budget) it else it.take(budget) + "\n…(truncated)"
                    }
                    SubagentStatus.CANCELLED -> "Status: cancelled."
                    else -> "Status: failed. ${run.error.orEmpty()}".trim()
                }
                "$title\n$body"
            }
        }
    }
}
