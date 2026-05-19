package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction

class DefaultSkillRegistry : SkillRegistry {
    private val skills = mutableMapOf<String, SkillDefinition>()

    fun register(skill: SkillDefinition) {
        skills[skill.name] = skill
    }

    override fun getSkill(name: String): SkillDefinition? = skills[name]
    
    override fun getAllSkills(): List<SkillDefinition> = skills.values.toList()

    private val settingsKeyToSkillId = mapOf(
        "file_read" to "read_file",
        "file_write" to "write_file",
        "file_list" to "list_files",
        "file_search" to "search_files",
        "file_diff" to "diff_file",
        "file_patch" to "patch_file"
    )

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val filteredSkills = if (allowedIds == null) {
            skills.values
        } else {
            val resolvedAllowedIds = allowedIds.map { settingsKeyToSkillId[it] ?: it }
            skills.values.filter { it.id in resolvedAllowedIds }
        }
        
        return filteredSkills.map { skill ->
            ProtocolTool(
                type = "function",
                function = ProtocolToolFunction(
                    name = skill.name,
                    description = skill.description,
                    parameters = skill.parametersSchema
                )
            )
        }
    }
}
