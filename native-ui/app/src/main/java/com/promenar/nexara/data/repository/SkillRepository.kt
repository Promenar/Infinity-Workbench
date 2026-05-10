package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import kotlinx.coroutines.flow.Flow

interface ISkillRepository {
    fun getAllCustomSkills(): Flow<List<CustomSkillEntity>>
    suspend fun insertCustomSkill(skill: CustomSkillEntity)
    suspend fun deleteCustomSkill(skill: CustomSkillEntity)
    suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean)

    fun getAllMcpServers(): Flow<List<McpServerEntity>>
    suspend fun insertMcpServer(server: McpServerEntity)
    suspend fun deleteMcpServer(server: McpServerEntity)
    suspend fun updateMcpServerEnabled(id: String, enabled: Boolean)
}

class SkillRepository(private val skillDao: SkillDao) : ISkillRepository {
    override fun getAllCustomSkills() = skillDao.getAllCustomSkills()
    override suspend fun insertCustomSkill(skill: CustomSkillEntity) = skillDao.insertCustomSkill(skill)
    override suspend fun deleteCustomSkill(skill: CustomSkillEntity) = skillDao.deleteCustomSkill(skill)
    override suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean) = skillDao.updateCustomSkillEnabled(id, enabled)

    override fun getAllMcpServers() = skillDao.getAllMcpServers()
    override suspend fun insertMcpServer(server: McpServerEntity) = skillDao.insertMcpServer(server)
    override suspend fun deleteMcpServer(server: McpServerEntity) = skillDao.deleteMcpServer(server)
    override suspend fun updateMcpServerEnabled(id: String, enabled: Boolean) = skillDao.updateMcpServerEnabled(id, enabled)
}
