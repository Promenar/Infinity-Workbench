package com.promenar.nexara.data.repository

import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.domain.model.ConnectionResult
import com.promenar.nexara.domain.model.ModelCapability
import com.promenar.nexara.domain.model.ModelSpec
import com.promenar.nexara.domain.model.ModelType
import com.promenar.nexara.domain.model.ProviderConfig
import com.promenar.nexara.domain.repository.IProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProviderRepository(
    private val providerManager: ProviderManager
) : IProviderRepository {

    override fun observeAll(): Flow<List<ProviderConfig>> {
        return providerManager.providers.map { items ->
            items.map { it.toDomain() }
        }
    }

    override suspend fun testConnection(providerId: String): ConnectionResult {
        val config = providerManager.getProviderConfig(providerId)
            ?: return ConnectionResult(false, null, "Provider not found: $providerId")
        val start = System.currentTimeMillis()
        return try {
            val provider = LlmProvider.builder()
                .protocolType(config.protocolType)
                .baseUrl(config.baseUrl)
                .apiKey(config.apiKey)
                .model(config.model)
                .build()
            provider.listModels()
            val latency = System.currentTimeMillis() - start
            ConnectionResult(true, latency, null)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            ConnectionResult(false, latency, e.message)
        }
    }

    override suspend fun fetchModels(providerId: String): List<ModelSpec> {
        val config = providerManager.getProviderConfig(providerId)
            ?: return emptyList()
        val provider = LlmProvider.builder()
            .protocolType(config.protocolType)
            .baseUrl(config.baseUrl)
            .apiKey(config.apiKey)
            .model(config.model)
            .build()
        return try {
            provider.listModels().map { modelId ->
                ModelSpec(
                    id = modelId,
                    name = modelId,
                    type = ModelType.CHAT,
                    capabilities = emptyList(),
                    providerId = providerId
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun save(config: ProviderConfig) {
        val dataProtocolType = config.protocolType.toData()
        if (config.id == "default") {
            providerManager.updateMainProvider(
                protocolType = dataProtocolType,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.defaultModel,
                name = config.name
            )
        } else {
            val item = ProviderListItem(
                id = config.id,
                name = config.name,
                typeName = dataProtocolType.displayName,
                baseUrl = config.baseUrl,
                model = config.defaultModel,
                protocolType = dataProtocolType,
                apiKey = config.apiKey
            )
            providerManager.addProvider(item)
        }
    }

    override suspend fun delete(id: String) {
        providerManager.deleteProvider(id)
    }

    private fun ProviderListItem.toDomain(): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        protocolType = protocolType.toDomain(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        defaultModel = model,
        isEnabled = apiKey.isNotBlank()
    )

    private fun ProtocolType.toDomain(): com.promenar.nexara.domain.model.ProtocolType = when (this) {
        is ProtocolType.Anthropic_Messages -> com.promenar.nexara.domain.model.ProtocolType.ANTHROPIC
        is ProtocolType.Google_VertexAI -> com.promenar.nexara.domain.model.ProtocolType.VERTEX_AI
        else -> com.promenar.nexara.domain.model.ProtocolType.OPENAI
    }

    private fun com.promenar.nexara.domain.model.ProtocolType.toData(): ProtocolType = when (this) {
        com.promenar.nexara.domain.model.ProtocolType.OPENAI -> ProtocolType.Generic_OpenAI_Compat
        com.promenar.nexara.domain.model.ProtocolType.ANTHROPIC -> ProtocolType.Anthropic_Messages
        com.promenar.nexara.domain.model.ProtocolType.VERTEX_AI -> ProtocolType.Google_VertexAI
    }
}
