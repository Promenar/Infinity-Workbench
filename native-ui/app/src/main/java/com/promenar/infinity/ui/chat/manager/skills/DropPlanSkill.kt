package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class DropPlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "drop_plan"
    override val name = "drop_plan"
    override val description = "终止当前任务，递归标记所有节点为 DROPPED。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"reason":{"type":"string","description":"终止原因"}},"required":["reason"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val reason = args.stringArgument("reason") ?: ""
        val planId = taskRepo.getPlan(context.sessionId)?.id
            ?: return planResult("drop_plan") {
                put("dropped", false)
                put("message", "当前没有活跃计划")
            }

        taskRepo.dropPlan(context.sessionId, reason)

        return planResult("drop_plan") {
            put("dropped", true)
            put("planId", planId)
        }
    }
}
