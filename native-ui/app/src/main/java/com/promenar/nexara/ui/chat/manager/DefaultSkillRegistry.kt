package com.promenar.nexara.ui.chat.manager

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction

class DefaultSkillRegistry : SkillRegistry {
    private val skills = mutableMapOf<String, SkillDefinition>()

    fun register(skill: SkillDefinition) {
        skills[skill.name] = skill
    }

    override fun getSkill(name: String): SkillDefinition? = skills[name]

    fun getAllTools(): List<ProtocolTool> = skills.values.map { skill ->
        ProtocolTool(
            type = "function",
            function = ProtocolToolFunction(
                name = skill.name,
                description = skill.description,
                parameters = getParametersJson(skill)
            )
        )
    }

    private fun getParametersJson(skill: SkillDefinition): String {
        return if (skill is ParameterizedSkill) {
            skill.parametersSchema
        } else {
            """{"type":"object","properties":{}}"""
        }
    }
}

interface ParameterizedSkill {
    val parametersSchema: String
}
