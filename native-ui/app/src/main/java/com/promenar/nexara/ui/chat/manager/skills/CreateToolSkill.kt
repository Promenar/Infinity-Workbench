package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.util.UUID

class CreateToolSkill(
    private val skillDao: SkillDao
) : SkillDefinition {
    override val id: String = "create_tool"
    override val name: String = "create_tool"
    override val description: String = "Create a new custom tool/skill for the assistant to use."
    override val mcpServerId: String? = null
    
    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "name": { "type": "string", "description": "Unique name of the tool (use snake_case)" },
                "description": { "type": "string", "description": "What the tool does" },
                "parametersSchema": { "type": "string", "description": "JSON Schema of the parameters" },
                "code": { "type": "string", "description": "The logic/implementation of the tool" }
            },
            "required": ["name", "description", "parametersSchema", "code"]
        }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val name = args["name"] as? String ?: return ToolResult("err", "Missing name", "error")
        val description = args["description"] as? String ?: ""
        val schema = args["parametersSchema"] as? String ?: "{}"
        val code = args["code"] as? String ?: ""

        val entity = CustomSkillEntity(
            id = "user_${UUID.randomUUID()}",
            name = name,
            description = description,
            parametersSchema = schema,
            code = code,
            type = "user"
        )
        
        return try {
            skillDao.insertCustomSkill(entity)
            ToolResult("create_${System.currentTimeMillis()}", "Successfully created tool: $name. You can now use it.", "success")
        } catch (e: Exception) {
            ToolResult("create_${System.currentTimeMillis()}", "Failed to create tool: ${e.message}", "error")
        }
    }
}
