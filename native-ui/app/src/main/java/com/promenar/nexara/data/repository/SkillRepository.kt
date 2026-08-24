package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.local.db.dao.McpDiscoveryRow
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private sealed interface McpSourceOverride {
        data class Present(val server: McpServerEntity) : McpSourceOverride
        data object Deleted : McpSourceOverride
    }

    private val mcpConfigRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val validSnapshotRevisions = ConcurrentHashMap<String, Long>()
    private val mcpSourceOverrides = ConcurrentHashMap<String, McpSourceOverride>()
    private val mcpMutationLocks = ConcurrentHashMap<String, Mutex>()

    override fun getAllCustomSkills() = skillDao.getAllCustomSkills()
    override suspend fun insertCustomSkill(skill: CustomSkillEntity) = skillDao.insertCustomSkill(skill)
    override suspend fun deleteCustomSkill(skill: CustomSkillEntity) = skillDao.deleteCustomSkill(skill)
    override suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean) = skillDao.updateCustomSkillEnabled(id, enabled)
    override suspend fun getAllEnabledCustomSkills() = skillDao.getAllEnabledCustomSkills()
    override suspend fun getEnabledCustomSkillByName(name: String) = skillDao.getEnabledCustomSkillByName(name)

    override fun getAllMcpServers() = skillDao.getAllMcpServers()
    override suspend fun insertMcpServer(server: McpServerEntity) = withMcpMutation(server.id) {
        invalidateMcpConfig(server.id)
        mcpSourceOverrides[server.id] = McpSourceOverride.Present(server)
        skillDao.insertMcpServer(server)
    }
    override suspend fun deleteMcpServer(server: McpServerEntity) = withMcpMutation(server.id) {
        invalidateMcpConfig(server.id)
        mcpSourceOverrides[server.id] = McpSourceOverride.Deleted
        skillDao.deleteMcpServer(server)
    }
    override suspend fun updateMcpServerEnabled(id: String, enabled: Boolean) = withMcpMutation(id) {
        invalidateMcpConfig(id)
        val current = skillDao.getMcpServer(id)
        mcpSourceOverrides[id] = current?.copy(enabled = enabled)
            ?.let(McpSourceOverride::Present)
            ?: McpSourceOverride.Deleted
        skillDao.updateMcpServerEnabledAndInvalidate(id, enabled)
    }
    override suspend fun updateMcpServerDefault(id: String, isDefault: Boolean) = skillDao.updateMcpServerDefault(id, isDefault)
    override suspend fun getMcpServer(id: String) = skillDao.getMcpServer(id)
    override suspend fun getAllEnabledMcpServers() = skillDao.getAllEnabledMcpServers()
    override suspend fun getAllMcpToolSnapshots() = skillDao.getAllMcpToolSnapshots()
    fun observeMcpDiscoveryRows(): Flow<List<McpDiscoveryRow>> = skillDao.observeMcpDiscoveryRows()
    override suspend fun getMcpToolSnapshots(serverId: String) = skillDao.getMcpToolSnapshots(serverId)
    override suspend fun replaceMcpToolSnapshotsIfServerUnchanged(
        serverId: String,
        expectedUrl: String,
        expectedType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ): Boolean = replaceMcpToolSnapshotsAtRevision(
        serverId = serverId,
        expectedUrl = expectedUrl,
        expectedType = expectedType,
        snapshots = snapshots,
        expectedConfigRevision = currentMcpConfigRevision(serverId),
    )

    suspend fun replaceMcpToolSnapshotsAtRevision(
        serverId: String,
        expectedUrl: String,
        expectedType: String,
        snapshots: List<McpToolSnapshotEntity>,
        expectedConfigRevision: Long,
    ): Boolean = withMcpMutation(serverId) {
        if (currentMcpConfigRevision(serverId) != expectedConfigRevision) return@withMcpMutation false
        val replaced = skillDao.replaceMcpToolSnapshotsIfServerUnchanged(
            serverId,
            expectedUrl,
            expectedType,
            snapshots,
        )
        if (replaced) validSnapshotRevisions[serverId] = expectedConfigRevision
        replaced
    }
    override suspend fun clearMcpToolSnapshots(serverId: String) = withMcpMutation(serverId) {
        invalidateMcpConfig(serverId)
        skillDao.clearMcpToolSnapshots(serverId)
    }

    fun currentMcpConfigRevision(serverId: String): Long =
        mcpConfigRevisions[serverId]?.get() ?: 0L

    fun isMcpSnapshotCurrent(serverId: String, expectedUrl: String, expectedType: String): Boolean {
        val revision = currentMcpConfigRevision(serverId)
        val validRevision = validSnapshotRevisions[serverId] ?: if (revision == 0L) 0L else Long.MIN_VALUE
        if (validRevision != revision) return false
        return when (val override = mcpSourceOverrides[serverId]) {
            null -> true
            McpSourceOverride.Deleted -> false
            is McpSourceOverride.Present -> override.server.enabled &&
                override.server.url == expectedUrl && override.server.type == expectedType
        }
    }

    private fun invalidateMcpConfig(serverId: String) {
        mcpConfigRevisions.computeIfAbsent(serverId) { AtomicLong() }.incrementAndGet()
        validSnapshotRevisions.remove(serverId)
    }

    private suspend fun <T> withMcpMutation(serverId: String, block: suspend () -> T): T =
        mcpMutationLocks.computeIfAbsent(serverId) { Mutex() }.withLock { block() }
}
