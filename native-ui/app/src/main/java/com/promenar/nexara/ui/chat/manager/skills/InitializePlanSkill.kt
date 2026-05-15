package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.data.model.ToolResult
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

class InitializePlanSkill(
    private val taskRepo: ITaskRepository
) : SkillDefinition {
    override val id = "initialize_plan"
    override val name = "initialize_plan"
    override val description = "创建任务计划树。每个会话同时只能有一个活跃任务。若已存在活跃任务则返回冲突信息。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"goal":{"type":"string","description":"任务目标描述"},"tree":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},"sortOrder":{"type":"integer"},"children":{"type":"array","items":{}}},"required":["id","title"]}}},"required":["goal","tree"]}"""

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val goal = args["goal"] as? String
            ?: return ToolResult("err", "缺少 goal 参数", "error")

        val treeRaw = args["tree"]
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

    private fun parseTree(raw: Any): List<TaskStep>? {
        return try {
            val jsonString = when (raw) {
                is String -> raw
                is List<*> -> {
                    val elements = raw.map { item ->
                        when (item) {
                            is Map<*, *> -> mapToJsonObject(item)
                            else -> return null
                        }
                    }
                    JsonArray(elements).toString()
                }
                else -> raw.toString()
            }
            val array = json.parseToJsonElement(jsonString).jsonArray
            array.map { parseStep(it.jsonObject) }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStep(obj: JsonObject): TaskStep {
        val children = obj["children"]?.jsonArray?.map { parseStep(it.jsonObject) } ?: emptyList()
        return TaskStep(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            sortOrder = obj["sortOrder"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            children = children
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToJsonObject(map: Map<*, *>): JsonObject {
        val pairs = (map as Map<String, Any?>).mapNotNull { (k, v) ->
            when (v) {
                is String -> k to JsonPrimitive(v)
                is Number -> k to JsonPrimitive(v.toInt())
                is Boolean -> k to JsonPrimitive(v)
                is List<*> -> k to JsonArray(v.map { item ->
                    when (item) {
                        is Map<*, *> -> mapToJsonObject(item)
                        is String -> JsonPrimitive(item)
                        else -> JsonPrimitive(item?.toString() ?: "")
                    }
                })
                null -> null
                else -> k to JsonPrimitive(v.toString())
            }
        }
        return JsonObject(pairs.toMap())
    }
}
