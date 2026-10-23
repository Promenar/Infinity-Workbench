package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.schedule.DefaultScheduledTaskController
import com.promenar.nexara.domain.schedule.*
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.*
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

/** 作为独立 registry 加入 ModularSkillRegistry；所有操作绑定执行上下文的会话。 */
fun scheduleSkillRegistry(controller: DefaultScheduledTaskController): SkillRegistry = DefaultSkillRegistry().apply {
    listOf("create", "list", "pause", "resume", "cancel").forEach { register(ScheduleSkill(it, controller)) }
}

private class ScheduleSkill(
    private val operation: String,
    private val controller: DefaultScheduledTaskController,
) : SkillDefinition {
    override val id = "schedule_$operation"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = if (operation == "create") ToolRisk.SCHEDULE else ToolRisk.SESSION_STATE
    override val description: String = when (operation) {
        "create" -> "在当前会话创建单次、间隔或 cron 定时任务，用于轮询、检查或提醒。创建始终需要用户批准；每次到点发起完整一轮。时区固定为创建时设备时区。非精确最短15分钟，精确最短5分钟；渠道或权限不支持时按至少15分钟降级。"
        "list" -> "列出当前会话的定时任务、下次触发时间和最近结果。"
        "pause" -> "暂停当前会话中的定时任务，保留记录。"
        "resume" -> "恢复当前会话中尚未过期或达到次数上限的定时任务。"
        else -> "取消并删除当前会话的定时任务。不再需要检查时主动取消。"
    }
    override val parametersSchema: String = when (operation) {
        // 只用 ToolSchemaValidator 支持的关键字；变体必填项与下限写在描述里，由 ScheduleCalculation 在运行时校验。
        "create" -> """{"type":"object","properties":{"name":{"type":"string","description":"1 到 60 字符"},"prompt":{"type":"string","description":"每次到点发给本会话的提示，1 到 4000 字符"},"trigger":{"type":"object","description":"once 需 at；interval 需 every_minutes；cron 需 expr","properties":{"type":{"type":"string","enum":["once","interval","cron"]},"at":{"type":"string","description":"once 必填：设备本地时间，例如2026-10-24T09:00"},"every_minutes":{"type":"integer","description":"interval 必填：间隔分钟，精确至少5，非精确至少15"},"expr":{"type":"string","description":"cron 必填：五段cron：分 时 日 月 周，支持*、数字、列表、范围、步长"}},"required":["type"],"additionalProperties":false},"exact":{"type":"boolean","default":false},"max_runs":{"type":"integer","description":"可选的触发次数上限，正整数"},"expires_at":{"type":"string","description":"ISO本地时间或含Z/偏移的时间"}},"required":["name","prompt","trigger"],"additionalProperties":false}"""
        "list" -> """{"type":"object","properties":{},"additionalProperties":false}"""
        else -> """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""
    }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        val result = buildJsonObject {
            when (operation) {
                "create" -> {
                    val created = controller.create(context.sessionId, ScheduleCalculation.parse(args, ZoneId.systemDefault().id))
                    put("task", taskJson(created.task))
                    put("id", created.task.id)
                    put("next_fire_at", created.task.nextFireAtMillis?.let(::JsonPrimitive) ?: JsonNull)
                    put("exact_effective", created.task.exactEffective)
                    created.notice?.let { put("notice", it) }
                }
                "list" -> put("tasks", JsonArray(controller.list(context.sessionId).map(::taskJson)))
                else -> {
                    val taskId = (args["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("id 必须是字符串。")
                    when (operation) {
                        "pause" -> controller.pauseForSession(context.sessionId, taskId)
                        "resume" -> controller.resumeForSession(context.sessionId, taskId)
                        "cancel" -> controller.deleteForSessionTask(context.sessionId, taskId)
                    }
                    put("id", taskId)
                    put("status", "ok")
                }
            }
        }
        ToolResult(id = "${id}_${UUID.randomUUID()}", content = result.toString())
    } catch (error: IllegalArgumentException) {
        ToolResult(id = "${id}_${UUID.randomUUID()}", content = buildJsonObject {
            put("status", "error"); put("message", error.message ?: "定时任务参数无效。")
        }.toString(), status = "error")
    }
}

private fun taskJson(task: ScheduledTask): JsonObject = buildJsonObject {
    put("id", task.id); put("name", task.name); put("prompt", task.prompt)
    put("enabled", task.enabled); put("exact_requested", task.exactRequested); put("exact_effective", task.exactEffective)
    put("next_fire_at", task.nextFireAtMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("run_count", task.runCount); put("max_runs", task.maxRuns?.let(::JsonPrimitive) ?: JsonNull)
    put("expires_at", task.expiresAtMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("last_fired_at", task.lastFiredAtMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("last_result", task.lastResult?.name?.let(::JsonPrimitive) ?: JsonNull)
    put("trigger", buildJsonObject {
        when (val trigger = task.trigger) {
            is ScheduleTrigger.Once -> { put("type", "once"); put("at", trigger.localDateTime); put("zone_id", trigger.zoneId) }
            is ScheduleTrigger.Interval -> { put("type", "interval"); put("every_minutes", trigger.everyMinutes) }
            is ScheduleTrigger.Cron -> { put("type", "cron"); put("expr", trigger.expression); put("zone_id", trigger.zoneId) }
        }
    })
}
