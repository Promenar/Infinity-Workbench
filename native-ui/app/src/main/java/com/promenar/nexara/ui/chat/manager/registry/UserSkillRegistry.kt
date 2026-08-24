package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.remote.protocol.ProtocolTool

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
