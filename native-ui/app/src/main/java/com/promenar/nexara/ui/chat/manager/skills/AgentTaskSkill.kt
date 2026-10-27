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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 派生子代理：后台回执与前台等待均复用应用级执行者。 */
class AgentTaskSkill(private val launcher: () -> SubagentLauncher) : SkillDefinition {
    override val id = SubagentLimits.TOOL_NAME
    override val name = SubagentLimits.TOOL_NAME
    override val description = "Delegate self-contained subtasks to application-owned subagents. Background is the default: return IDs immediately and receive conclusions automatically. " +
        "Use background=false when the results are required to continue, or agent_wait to wait later. Stopping this turn does not stop subagents. Each subagent works with this session's " +
        "model, workspace and approval rules, cannot ask the user anything, and returns only its final answer. List up to " +
        "${SubagentLimits.MAX_PARALLEL} independent tasks in one call to run them in parallel. Write each prompt as a complete brief: " +
        "goal, relevant paths or facts, constraints and the expected answer format. Use preset \"explorer\" for read-only research. " +
        "Use each task's optional tools list to restrict the tools available to that subagent; it cannot grant tools outside tool_profile."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{
        "type":"object",
        "properties":{
            "background":{"type":"boolean","description":"Default true; false waits for the results"},
            "timeout_seconds":{"type":"number","description":"Positive wait timeout in seconds; only used when background=false"},
            "max_turns":{"type":"integer","description":"Optional positive turn limit for each subagent"},
            "tasks":{
                "type":"array",
                "description":"1 to ${SubagentLimits.MAX_PARALLEL} independent tasks",
                "items":{
                    "type":"object",
                    "properties":{
                        "description":{"type":"string","description":"Short task title shown to the user, at most 60 characters"},
                        "prompt":{"type":"string","description":"Complete, self-contained instructions for the subagent"},
                        "preset":{"type":"string","enum":["explorer"],"description":"Optional preset; explorer is read-only"},
                        "tool_profile":{"type":"string","enum":["read_only","inherit"],"description":"read_only allows only read tools; inherit uses this session's tools. Defaults to inherit, or read_only for explorer"},
                        "tools":{"type":"array","items":{"type":"string"},"description":"Optional tool-name allowlist to further restrict tool_profile; cannot grant tools outside that profile. Unknown or unavailable names are ignored and listed in the result; an empty list disables all tools"}
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
            launcher().launch(context, stepId, specs, background = (args["background"] as? JsonPrimitive)?.booleanOrNull ?: true,
                timeoutMillis = subagentWaitTimeout(args), maxTurns = (args["max_turns"] as? JsonPrimitive)?.intOrNull)
        } catch (unavailable: SubagentModelUnavailable) {
            // 只回显启动器给出的固定原因；其它异常交给工具执行器统一脱敏。
            return failure(unavailable.message ?: "Subagents are unavailable in this session.")
        }
        return ToolResult(
            id = "agent_task_${System.currentTimeMillis()}",
            content = formatResults(runs),
            status = "success",
        )
    }

    internal fun parse(args: JsonObject): List<SubagentSpec>? {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return null
        if (args["max_turns"] != null && ((args["max_turns"] as? JsonPrimitive)?.intOrNull ?: 0) <= 0) return null
        if (!validSubagentWaitTimeout(args)) return null
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
                allowedTools = (task["tools"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.distinct(),
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

        /** 同一返回格式用于后台进度、等待回执和已结束结论，正文不截断。 */
        internal fun formatResults(runs: List<SubagentRun>): String = runs.joinToString("\n\n") { run ->
            val detached = run.events.lastOrNull { it.kind == com.promenar.nexara.data.subagent.SubagentEvent.Kind.DETACH }
            val note = when (detached?.by) {
                com.promenar.nexara.data.subagent.SubagentEvent.Actor.USER -> "\n用户已将子代理转入后台。"
                com.promenar.nexara.data.subagent.SubagentEvent.Actor.TIMEOUT -> "\n等待超时，子代理继续在后台运行。"
                else -> ""
            }
            "## Subagent ${run.index + 1}: ${run.description}\nID: ${run.id}\nStatus: ${run.status.name.lowercase()}." +
                " Steps: ${run.step}; tokens: ${run.totalTokens}." + note +
                (run.ignoredTools.takeIf { it.isNotEmpty() }?.let { "\nIgnored tools: ${it.joinToString(", ")}" }.orEmpty()) +
                (run.result?.let { "\n$it" } ?: run.error?.let { "\n$it" }.orEmpty()) +
                (run.resultPath?.let { "\nResult path: $it" }.orEmpty())
        }

    }
}
