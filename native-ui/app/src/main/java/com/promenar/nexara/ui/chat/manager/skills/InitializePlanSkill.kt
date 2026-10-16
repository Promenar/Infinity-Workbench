package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.promenar.nexara.ui.chat.manager.registry.arrayArgument
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

class InitializePlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "initialize_plan"
    override val name = "initialize_plan"
    override val description = "创建任务计划树。每个会话同时只能有一个活跃计划；已有未完成计划时返回冲突信息。" +
        "步骤 ID 由你指定（如 1、1.1），在本计划内必须唯一，后续 update_plan 用它引用步骤。第一个叶子步骤自动设为进行中。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"goal":{"type":"string","description":"任务目标描述"},"tree":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string","description":"本计划内唯一的步骤 ID"},"title":{"type":"string"},"description":{"type":"string"},"sortOrder":{"type":"integer"},"children":{"type":"array","items":{}}},"required":["id","title"]}}},"required":["goal","tree"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val goal = args.stringArgument("goal")?.takeIf(String::isNotBlank)
            ?: return planError("initialize_plan", "INVALID_ARGUMENTS", "缺少 goal 参数。")

        val treeRaw = args.arrayArgument("tree")
            ?: return planError("initialize_plan", "INVALID_ARGUMENTS", "缺少 tree 参数。")

        val tree = parseTree(treeRaw)
            ?: return planError("initialize_plan", "INVALID_ARGUMENTS", "tree 格式无效：每个步骤需要 id 与 title。")

        val state = try {
            taskRepo.initializePlan(context.sessionId, goal, tree)
        } catch (e: IllegalArgumentException) {
            return planError("initialize_plan", "INVALID_TREE", e.message)
        }

        return if (state.status == "conflict") {
            val (done, total) = taskRepo.countLeafProgress(state.steps)
            planResult("initialize_plan") {
                put("conflict", true)
                put("existingPlanId", state.id)
                put("existingGoal", state.title)
                put("existingProgress", "$done/$total done")
                put("suggestion", "当前已有未完成的计划。用 update_plan 继续推进；若确需替换，调用 drop_plan 后再创建。")
            }
        } else {
            val (_, total) = taskRepo.countLeafProgress(state.steps)
            planResult("initialize_plan") {
                putPlanSummary(taskRepo, state)
                put("message", "任务已创建，共 $total 个步骤")
            }
        }
    }

    private fun parseTree(raw: JsonArray): List<TaskStep>? = raw.map { item ->
        parseStep(item as? JsonObject ?: return null) ?: return null
    }

    private fun parseStep(obj: JsonObject): TaskStep? {
        val children = (obj["children"] as? JsonArray)?.map { child ->
            parseStep(child as? JsonObject ?: return null) ?: return null
        } ?: emptyList()
        return TaskStep(
            id = obj.stringArgument("id") ?: return null,
            title = obj.stringArgument("title") ?: return null,
            description = obj.stringArgument("description") ?: "",
            sortOrder = obj.intArgument("sortOrder") ?: 0,
            children = children
        )
    }
}
