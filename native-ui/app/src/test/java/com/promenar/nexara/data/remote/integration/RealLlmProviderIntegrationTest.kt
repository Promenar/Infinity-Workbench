package com.promenar.nexara.data.remote.integration

import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.stableModelId
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.ImageInput
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.ui.settings.ModelInfo
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class RealLlmProviderIntegrationTest {
    private var loadedConfig: IntegrationTestConfig? = null

    @Before
    fun loadConfiguration() {
        assumeTrue(
            "real LLM integration is not explicitly enabled",
            System.getProperty(ENABLE_PROPERTY) == "true",
        )
        val loaded = IntegrationTestConfig.load()
        assumeTrue(
            "integration credentials unavailable",
            loaded is IntegrationTestConfigLoadResult.Available,
        )
        loadedConfig = (loaded as IntegrationTestConfigLoadResult.Available).config
    }

    @After
    fun clearCredentialBuffer() {
        loadedConfig?.close()
        loadedConfig = null
    }

    @Test
    fun `同一router依次验证四类真实模型且每类仅请求一次`() = runBlocking {
        val config = loadedConfig ?: failSafely("integration configuration unavailable")
        val roles = listOf(
            ModelRole(config.fastTextModel, multimodal = false),
            ModelRole(config.reasoningModel, multimodal = false),
            ModelRole(config.multimodalModel, multimodal = true),
            ModelRole(config.balancedMultimodalModel, multimodal = true),
        )
        if (roles.map { it.remoteModelId }.distinct().size != roles.size) {
            failSafely("integration model roles must be distinct")
        }

        val requestCounts = linkedMapOf<String, Int>()
        val providerId = "integration-provider"
        val models = roles.associate { role ->
            val stableId = stableModelId(providerId, role.remoteModelId)
            stableId to ModelInfo(
                name = "integration-model",
                id = stableId,
                description = "integration-only",
                enabled = true,
                providerName = "integration-provider",
                providerId = providerId,
                remoteModelId = role.remoteModelId,
            )
        }
        val router = DefaultProviderRequestRouter(
            modelResolver = models::get,
            providerResolver = { id ->
                if (id == providerId) ProviderListItem(
                    id = providerId,
                    name = "integration-provider",
                    protocolType = ProtocolType.Generic_OpenAI_Compat,
                    hasApiKey = true,
                    enabled = true,
                ) else null
            },
            configResolver = { id ->
                if (id == providerId) ProviderConfig(
                    protocolType = ProtocolType.Generic_OpenAI_Compat,
                    baseUrl = "https://integration.invalid",
                    apiKey = "configured-in-integration-environment",
                    name = "integration-provider",
                ) else null
            },
            clientFactory = { routed ->
                requestCounts[routed.defaultModel] = requestCounts.getOrDefault(routed.defaultModel, 0) + 1
                val credential = config.apiKey.concatToString()
                UnifiedLlmClient(
                    providerConfigResolver = {
                        UnifiedProviderConfig(
                            protocolType = ProtocolType.Generic_OpenAI_Compat,
                            baseUrl = config.baseUrl,
                            apiKey = credential,
                            defaultModel = routed.defaultModel,
                        )
                    }
                )
            },
        )

        roles.forEach { role ->
            val stableId = stableModelId(providerId, role.remoteModelId)
            val resolved = router.resolve(stableId)
            if (resolved !is ProviderResolution.Success ||
                resolved.value.remoteModelId != role.remoteModelId
            ) failSafely("provider router resolution failed")
            val client = router.createClient((resolved as ProviderResolution.Success).value)
            verifyCompletedStream(client, role.remoteModelId, role.multimodal)
        }

        if (requestCounts.size != roles.size || requestCounts.values.any { it != 1 }) {
            failSafely("provider request count mismatch")
        }
    }

    private suspend fun verifyCompletedStream(
        client: UnifiedLlmClient,
        model: String,
        multimodal: Boolean,
    ) {
        var payloadSeen = false
        var doneSeen = false
        var errorSeen = false
        try {
            withTimeout(150_000) {
                client.sendStream(request(model, multimodal), StreamConfig()).collect { chunk ->
                    when (chunk) {
                        is StreamChunk.TextDelta -> if (
                            chunk.content.isNotBlank() || chunk.reasoning?.isNotBlank() == true
                        ) payloadSeen = true
                        is StreamChunk.Thinking -> if (chunk.content.isNotBlank()) payloadSeen = true
                        is StreamChunk.Error -> errorSeen = true
                        StreamChunk.Done -> doneSeen = true
                        else -> Unit
                    }
                }
            }
        } catch (error: Throwable) {
            failSafely("real LLM stream execution failed for $model (${error::class.simpleName})")
        }
        if (!payloadSeen || !doneSeen || errorSeen) {
            failSafely(
                "real LLM stream contract failed for $model " +
                    "(payload=$payloadSeen, done=$doneSeen, error=$errorSeen)",
            )
        }
    }

    private fun request(model: String, multimodal: Boolean): StreamTextParams = StreamTextParams(
        model = model,
        messages = listOf(
            ProtocolMessage(
                role = "user",
                content = if (multimodal) MULTIMODAL_PROMPT else TEXT_PROMPT,
                imageUrls = if (multimodal) listOf(
                    ImageInput(base64 = ONE_PIXEL_PNG_BASE64, mimeType = "image/png")
                ) else null,
            )
        ),
        maxOutputTokens = 64,
        streamTimeout = 120_000,
    )

    private fun failSafely(message: String): Nothing {
        fail(message)
        error("unreachable")
    }

    private data class ModelRole(
        val remoteModelId: String,
        val multimodal: Boolean,
    )

    private companion object {
        const val ENABLE_PROPERTY = "nexara.realLlmIntegration"
        const val TEXT_PROMPT = "Reply with one short word."
        const val MULTIMODAL_PROMPT = "Name the dominant visual property in one short word."
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
