package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.ParameterizedSkill
import com.promenar.nexara.ui.chat.manager.SkillDefinition
import com.promenar.nexara.ui.chat.manager.SkillExecutionContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CurrentTimeSkill : SkillDefinition, ParameterizedSkill {
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
            id = "result_${System.currentTimeMillis()}",
            content = formatted
        )
    }
}
