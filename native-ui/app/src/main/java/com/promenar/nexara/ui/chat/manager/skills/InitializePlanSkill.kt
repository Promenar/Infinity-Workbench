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
import com.promenar.nexara.ui.chat.manager.registry.arrayArgument
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

class InitializePlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "initialize_plan"
    override val name = "initialize_plan"
    override val description = "创建任务计划树。每个会话同时只能有一个活跃任务。若已存在活跃任务则返回冲突信息。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"goal":{"type":"string","description":"任务目标描述"},"tree":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},"sortOrder":{"type":"integer"},"children":{"type":"array","items":{}}},"required":["id","title"]}}},"required":["goal","tree"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val goal = args.stringArgument("goal")
            ?: return ToolResult("err", "缺少 goal 参数", "error")

        val treeRaw = args.arrayArgument("tree")
            ?: return ToolResult("err", "缺少 tree 参数", "error")

        val tree = parseTree(treeRaw)
            ?: return ToolResult("err", "tree 格式无效", "error")

        val state = taskRepo.initializePlan(context.sessionId, goal, tree)

        return if (state.status == "conflict") {
            val (done, total) = taskRepo.countLeafProgress(state.steps)
            ToolResult(
                id = "initialize_plan_${System.currentTimeMillis()}",
                content = buildString {
                    appendLine("{\"conflict\":true,")
                    appendLine(" \"existingPlanId\":\"${state.id}\",")
                    appendLine(" \"existingGoal\":\"${state.title}\",")
                    appendLine(" \"existingProgress\":\"$done/$total done\",")
                    appendLine(" \"suggestion\":\"当前已有活跃任务。可调用 drop_plan 终止后重试，或向用户询问是否替换。\"}")
                }
            )
        } else {
            val (done, total) = taskRepo.countLeafProgress(state.steps)
            ToolResult(
                id = "initialize_plan_${System.currentTimeMillis()}",
                content = buildString {
                    appendLine("{\"planId\":\"${state.id}\",")
                    appendLine(" \"goal\":\"$goal\",")
                    appendLine(" \"message\":\"任务已创建，共 $total 个步骤\"}")
                }
            )
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
