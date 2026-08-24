package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.repository.PlanPatchOp
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.data.repository.TaskRepository
import com.promenar.nexara.ui.chat.manager.registry.arrayArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UpdatePlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "update_plan"
    override val name = "update_plan"
    override val description = "增量修改任务计划。支持 set_status（仅叶节点）、add_step、remove_step、move_step、update_title、set_note 操作。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"operations":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["set_status","add_step","remove_step","move_step","update_title","set_note"]},"stepId":{"type":"string"},"parentId":{"type":"string"},"payload":{"type":"object","properties":{"status":{"type":"string"},"note":{"type":"string"},"title":{"type":"string"},"sortOrder":{"type":"string"},"newParentId":{"type":"string"},"newSortOrder":{"type":"string"}}}},"required":["action"]}}},"required":["operations"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val opsRaw = args.arrayArgument("operations")
            ?: return ToolResult("err", "缺少 operations 参数", "error")

        val operations = parseOperations(opsRaw)
            ?: return ToolResult("err", "operations 格式无效", "error")

        if (operations.isEmpty()) {
            return ToolResult("err", "operations 不能为空", "error")
        }

        return try {
            val state = taskRepo.updatePlan(context.sessionId, operations)
            val (done, total) = taskRepo.countLeafProgress(state.steps)
            ToolResult(
                id = "update_plan_${System.currentTimeMillis()}",
                content = buildString {
                    appendLine("{\"applied\":${operations.size},")
                    appendLine(" \"currentPlan\":{")
                    appendLine("  \"planId\":\"${state.id}\",")
                    appendLine("  \"status\":\"${state.status}\",")
                    appendLine("  \"leafProgress\":{\"done\":$done,\"total\":$total},")
                    appendLine("  \"currentFocusStepId\":\"${state.currentFocusStepId ?: ""}\"")
                    appendLine(" }}")
                }
            )
        } catch (e: TaskRepository.ParentStatusDerivedException) {
            ToolResult(
                id = "update_plan_${System.currentTimeMillis()}",
                content = buildString {
                    appendLine("{\"error\":\"PARENT_STATUS_DERIVED\",")
                    appendLine(" \"stepId\":\"${e.stepId}\",")
                    appendLine(" \"message\":\"步骤 '${e.title}' 是父节点（含 ${e.childCount} 个子步骤），其状态由子节点自动派生，不可直接设置。\",")
                    appendLine(" \"suggestion\":\"请改为对子步骤执行 set_status。父节点状态会自动更新。\"}")
                },
                status = "error"
            )
        }
    }

    private fun parseOperations(raw: kotlinx.serialization.json.JsonArray): List<PlanPatchOp>? = raw.map { item ->
        val operation = item as? JsonObject ?: return null
        val payload = (operation["payload"] as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        }
        PlanPatchOp(
            action = operation.stringArgument("action") ?: return null,
            stepId = operation.stringArgument("stepId"),
            parentId = operation.stringArgument("parentId"),
            payload = payload,
        )
    }
}
