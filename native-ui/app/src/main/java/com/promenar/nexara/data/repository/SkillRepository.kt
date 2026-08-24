package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import kotlinx.coroutines.flow.Flow

interface ISkillRepository {
    fun getAllCustomSkills(): Flow<List<CustomSkillEntity>>
    suspend fun insertCustomSkill(skill: CustomSkillEntity)
    suspend fun deleteCustomSkill(skill: CustomSkillEntity)
    suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean)
    suspend fun getAllEnabledCustomSkills(): List<CustomSkillEntity>
    suspend fun getEnabledCustomSkillByName(name: String): CustomSkillEntity?

    fun getAllMcpServers(): Flow<List<McpServerEntity>>
    suspend fun insertMcpServer(server: McpServerEntity)
    suspend fun deleteMcpServer(server: McpServerEntity)
    suspend fun updateMcpServerEnabled(id: String, enabled: Boolean)
    suspend fun updateMcpServerDefault(id: String, isDefault: Boolean)
    suspend fun getMcpServer(id: String): McpServerEntity?
    suspend fun getAllEnabledMcpServers(): List<McpServerEntity>
    suspend fun getAllMcpToolSnapshots(): List<McpToolSnapshotEntity>
    suspend fun getMcpToolSnapshots(serverId: String): List<McpToolSnapshotEntity>
    suspend fun replaceMcpToolSnapshotsIfServerUnchanged(
        serverId: String,
        expectedUrl: String,
        expectedType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ): Boolean
    suspend fun clearMcpToolSnapshots(serverId: String)
}

class SkillRepository(private val skillDao: SkillDao) : ISkillRepository {
    override fun getAllCustomSkills() = skillDao.getAllCustomSkills()
    override suspend fun insertCustomSkill(skill: CustomSkillEntity) = skillDao.insertCustomSkill(skill)
    override suspend fun deleteCustomSkill(skill: CustomSkillEntity) = skillDao.deleteCustomSkill(skill)
    override suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean) = skillDao.updateCustomSkillEnabled(id, enabled)
    override suspend fun getAllEnabledCustomSkills() = skillDao.getAllEnabledCustomSkills()
    override suspend fun getEnabledCustomSkillByName(name: String) = skillDao.getEnabledCustomSkillByName(name)

    override fun getAllMcpServers() = skillDao.getAllMcpServers()
    override suspend fun insertMcpServer(server: McpServerEntity) = skillDao.insertMcpServer(server)
    override suspend fun deleteMcpServer(server: McpServerEntity) = skillDao.deleteMcpServer(server)
    override suspend fun updateMcpServerEnabled(id: String, enabled: Boolean) =
        skillDao.updateMcpServerEnabledAndInvalidate(id, enabled)
    override suspend fun updateMcpServerDefault(id: String, isDefault: Boolean) = skillDao.updateMcpServerDefault(id, isDefault)
    override suspend fun getMcpServer(id: String) = skillDao.getMcpServer(id)
    override suspend fun getAllEnabledMcpServers() = skillDao.getAllEnabledMcpServers()
    override suspend fun getAllMcpToolSnapshots() = skillDao.getAllMcpToolSnapshots()
    override suspend fun getMcpToolSnapshots(serverId: String) = skillDao.getMcpToolSnapshots(serverId)
    override suspend fun replaceMcpToolSnapshotsIfServerUnchanged(
        serverId: String,
        expectedUrl: String,
        expectedType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ) = skillDao.replaceMcpToolSnapshotsIfServerUnchanged(serverId, expectedUrl, expectedType, snapshots)
    override suspend fun clearMcpToolSnapshots(serverId: String) = skillDao.clearMcpToolSnapshots(serverId)
}
