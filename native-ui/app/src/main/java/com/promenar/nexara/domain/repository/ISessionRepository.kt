package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface ISessionRepository {
    fun observeByAgent(agentId: String): Flow<List<Session>>
    fun observeById(id: String): Flow<Session?>
    suspend fun create(agentId: String, modelId: String): Session
    suspend fun updateTitle(id: String, title: String)
    suspend fun delete(id: String)
}
