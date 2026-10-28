package com.promenar.infinity.ui.chat.manager.registry

import com.promenar.infinity.data.repository.SkillRepository
import com.promenar.infinity.data.remote.protocol.ProtocolTool

class UserSkillRegistry(
    private val repository: SkillRepository
) : SkillRegistry {

    override fun getSkill(name: String): SkillDefinition? {
        return null
    }

    override fun getAllSkills(): List<SkillDefinition> {
        return emptyList()
    }

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        return emptyList()
    }
}
