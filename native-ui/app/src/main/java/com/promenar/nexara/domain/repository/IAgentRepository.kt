package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.Agent
import kotlinx.coroutines.flow.Flow

interface IAgentRepository {
    fun observeAll(): Flow<List<Agent>>
    fun observeById(id: String): Flow<Agent?>
    suspend fun create(agent: Agent)
    suspend fun update(agent: Agent)
    suspend fun delete(id: String)
    suspend fun getById(id: String): Agent?
}
