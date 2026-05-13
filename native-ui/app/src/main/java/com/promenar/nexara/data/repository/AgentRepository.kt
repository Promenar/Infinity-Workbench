package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.AgentDao
import com.promenar.nexara.data.mapper.AgentMapper
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.repository.IAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgentRepository(
    private val agentDao: AgentDao
) : IAgentRepository {
    override fun observeAll(): Flow<List<Agent>> =
        agentDao.observeAll().map { list -> list.map { AgentMapper.toDomain(it) } }

    override fun observeById(id: String): Flow<Agent?> =
        agentDao.observeAll().map { list -> list.find { it.id == id }?.let { AgentMapper.toDomain(it) } }

    override suspend fun create(agent: Agent) {
        agentDao.insert(AgentMapper.toEntity(agent))
    }

    override suspend fun update(agent: Agent) {
        agentDao.update(AgentMapper.toEntity(agent))
    }

    override suspend fun delete(id: String) {
        agentDao.deleteById(id)
    }

    override suspend fun getById(id: String): Agent? =
        agentDao.getById(id)?.let { AgentMapper.toDomain(it) }
}
