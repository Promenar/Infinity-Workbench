package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

class UserSkillRegistry(
    private val repository: SkillRepository
) : SkillRegistry {

    override fun getSkill(name: String): SkillDefinition? {
        val entity = runBlocking { repository.getEnabledCustomSkillByName(name) }
        return entity?.let {
            CustomDatabaseSkill(it.id, it.name, it.description, it.parametersSchema, it.code)
        }
    }

    override fun getAllSkills(): List<SkillDefinition> {
        val entities = runBlocking { repository.getAllEnabledCustomSkills() }
        return entities.map {
            CustomDatabaseSkill(it.id, it.name, it.description, it.parametersSchema, it.code)
        }
    }

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val skills = getAllSkills()
        val filtered = if (allowedIds == null) skills else skills.filter { it.id in allowedIds }
        return filtered.map(SkillDefinition::toProtocolTool)
    }
}

class CustomDatabaseSkill(
    override val id: String,
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
    private val code: String
) : SkillDefinition {
    override val sourceId: String = "custom"
    override val mcpServerId: String? = null

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        return ToolResult(
            id = "user_${System.currentTimeMillis()}",
            content = "Custom skill '$name' was called with args: ${args.entries.joinToString { "${it.key}=${it.value}" }}. However, sandbox execution is not yet implemented. Code to execute: ${code.take(200)}",
            status = "success"
        )
    }
}
