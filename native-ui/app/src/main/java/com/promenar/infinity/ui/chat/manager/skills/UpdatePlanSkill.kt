package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.domain.repository.PlanPatchOp
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.data.repository.TaskRepository
import com.promenar.infinity.ui.chat.manager.registry.arrayArgument
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UpdatePlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "update_plan"
    override val name = "update_plan"
    override val description = "增量修改当前任务计划。set_status 只能用于叶子步骤，状态取值为 todo（未开始）、doing（进行中，同时只有一个）、" +
        "done（完成）、dropped（放弃）；父步骤状态由子步骤自动派生。其它操作：add_step、remove_step、move_step、update_title、set_note。" +
        "stepId 使用 initialize_plan 或 get_plan 返回的步骤 ID。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"operations":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["set_status","add_step","remove_step","move_step","update_title","set_note"]},"stepId":{"type":"string","description":"目标步骤 ID；add_step 时为新步骤 ID（可省略）"},"parentId":{"type":"string","description":"add_step 的父步骤 ID；省略表示顶层"},"payload":{"type":"object","properties":{"status":{"type":"string","enum":["todo","doing","done","dropped"]},"note":{"type":"string"},"title":{"type":"string"},"sortOrder":{"type":"integer"},"newParentId":{"type":"string"},"newSortOrder":{"type":"integer"}}}},"required":["action"]}}},"required":["operations"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val opsRaw = args.arrayArgument("operations")
            ?: return planError("update_plan", "INVALID_ARGUMENTS", "缺少 operations 参数。")

        val operations = parseOperations(opsRaw)
            ?: return planError("update_plan", "INVALID_ARGUMENTS", "operations 格式无效：每项必须是对象，payload 的值必须是字符串或数字。")

        if (operations.isEmpty()) {
            return planError("update_plan", "INVALID_ARGUMENTS", "operations 不能为空。")
        }

        return try {
            val state = taskRepo.updatePlan(context.sessionId, operations)
            planResult("update_plan") {
                put("applied", operations.size)
                put("currentPlan", buildJsonObject { putPlanSummary(taskRepo, state) })
            }
        } catch (e: TaskRepository.ParentStatusDerivedException) {
            planError(
                "update_plan",
                "PARENT_STATUS_DERIVED",
                e.message,
                "请改为对子步骤执行 set_status，父步骤状态会自动更新。",
            )
        } catch (e: TaskRepository.TaskCycleException) {
            planError("update_plan", "INVALID_MOVE", e.message)
        } catch (e: IllegalStateException) {
            planError("update_plan", "NO_ACTIVE_PLAN", e.message, "先调用 initialize_plan 创建计划。")
        } catch (e: IllegalArgumentException) {
            planError("update_plan", "INVALID_OPERATION", e.message, PLAN_STATUS_HINT)
        }
    }

    private fun parseOperations(raw: kotlinx.serialization.json.JsonArray): List<PlanPatchOp>? = raw.map { item ->
        val operation = item as? JsonObject ?: return null
        val payload = (operation["payload"] as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.content ?: return null
        }
        PlanPatchOp(
            action = operation.stringArgument("action") ?: return null,
            stepId = operation.stringArgument("stepId"),
            parentId = operation.stringArgument("parentId"),
            payload = payload,
        )
    }
}
