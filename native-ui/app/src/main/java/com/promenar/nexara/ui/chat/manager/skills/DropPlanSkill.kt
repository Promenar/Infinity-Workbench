package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext

class DropPlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "drop_plan"
    override val name = "drop_plan"
    override val description = "终止当前任务，递归标记所有节点为 DROPPED。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"reason":{"type":"string","description":"终止原因"}},"required":["reason"]}"""

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val reason = args["reason"] as? String ?: ""

        val state = taskRepo.getPlan(context.sessionId)
        val planId = state?.id ?: ""

        taskRepo.dropPlan(context.sessionId, reason)

        return ToolResult(
            id = "drop_plan_${System.currentTimeMillis()}",
            content = "{\"dropped\":true,\"planId\":\"$planId\"}"
        )
    }
}
