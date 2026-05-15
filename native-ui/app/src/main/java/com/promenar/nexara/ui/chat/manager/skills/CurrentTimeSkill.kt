package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 系统时间查询技能 — 已废弃为被动注入。
 *
 * 时间已通过 ContextBuilder.buildSystemPrompt() 在每轮对话中自动注入到 System Prompt
 * （格式: [System Time: yyyy-MM-dd HH:mm:ss 星期X]），LLM 无需主动调用此工具。
 * 保留此类仅用于向后兼容或未来可能的精确时间查询场景。
 */
@Deprecated("时间已通过 ContextBuilder 被动注入，无需注册为可调用工具")
class CurrentTimeSkill : SkillDefinition {
    override val id = "current_time"
    override val name = "current_time"
    override val description = "Get the current date and time"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override suspend fun execute(
        args: Map<String, Any>,
        context: SkillExecutionContext
    ): ToolResult {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val formatted = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)
        return ToolResult(
            id = "result_${System.nanoTime()}",
            content = formatted
        )
    }
}
