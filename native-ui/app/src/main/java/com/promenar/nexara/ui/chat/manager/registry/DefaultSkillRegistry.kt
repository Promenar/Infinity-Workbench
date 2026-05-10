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

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val filteredSkills = if (allowedIds == null) {
            skills.values
        } else {
            skills.values.filter { it.id in allowedIds }
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
