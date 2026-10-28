package com.promenar.infinity.domain.repository

import com.promenar.infinity.domain.model.ConnectionResult
import com.promenar.infinity.domain.model.ModelSpec
import com.promenar.infinity.domain.model.ProviderConfig
import kotlinx.coroutines.flow.Flow

interface IProviderRepository {
    fun observeAll(): Flow<List<ProviderConfig>>
    suspend fun testConnection(providerId: String): ConnectionResult
    suspend fun fetchModels(providerId: String): List<ModelSpec>
    suspend fun save(config: ProviderConfig)
    suspend fun delete(id: String)
}
