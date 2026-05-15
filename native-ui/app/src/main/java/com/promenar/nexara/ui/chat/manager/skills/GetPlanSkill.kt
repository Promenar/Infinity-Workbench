package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class GetPlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "get_plan"
    override val name = "get_plan"
    override val description = "读取当前任务完整树。返回含实时派生的父节点状态和叶节点进度。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{}}"""

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val state = taskRepo.getPlan(context.sessionId)
            ?: return ToolResult(
                id = "get_plan_${System.currentTimeMillis()}",
                content = "{\"active\":false,\"message\":\"当前没有活跃任务\"}"
            )

        val (done, total) = taskRepo.countLeafProgress(state.steps)
        val focusStep = state.steps.flatMap { flatten(it) }.find { it.id == state.currentFocusStepId }

        return ToolResult(
            id = "get_plan_${System.currentTimeMillis()}",
            content = buildString {
                appendLine("{\"planId\":\"${state.id}\",")
                appendLine(" \"title\":\"${state.title}\",")
                appendLine(" \"status\":\"${state.status}\",")
                appendLine(" \"leafProgress\":{\"done\":$done,\"total\":$total},")
                if (focusStep != null) {
                    appendLine(" \"currentFocus\":{\"id\":\"${focusStep.id}\",\"title\":\"${focusStep.title}\",\"status\":\"${focusStep.status}\"},")
                }
                appendLine(" \"tree\":${json.encodeToString(state.steps)}}")
            }
        )
    }

    private fun flatten(step: com.promenar.nexara.data.model.TaskStep): List<com.promenar.nexara.data.model.TaskStep> {
        return listOf(step) + step.children.flatMap { flatten(it) }
    }
}
