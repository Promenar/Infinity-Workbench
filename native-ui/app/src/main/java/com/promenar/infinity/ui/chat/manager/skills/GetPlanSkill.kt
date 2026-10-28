package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject

class GetPlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "get_plan"
    override val name = "get_plan"
    override val description = "读取当前任务完整树。返回含实时派生的父节点状态和叶节点进度。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{}}"""

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val state = taskRepo.getPlan(context.sessionId)
            ?: return planResult("get_plan") {
                put("active", false)
                put("message", "当前没有活跃计划")
            }

        val focusStep = flattenSteps(state.steps).find { it.id == state.currentFocusStepId }
        return planResult("get_plan") {
            put("active", true)
            putPlanSummary(taskRepo, state)
            focusStep?.let { step ->
                put("currentFocus", buildJsonObject {
                    put("id", step.id)
                    put("title", step.title)
                    put("status", step.status)
                })
            }
            put("tree", json.encodeToJsonElement(state.steps))
        }
    }
}
