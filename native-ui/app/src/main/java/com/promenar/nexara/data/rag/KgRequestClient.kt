package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolFactory
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 所有 KG 请求先解析稳定模型身份；解析失败时禁止使用默认提供商。 */
class KgRequestClient(
    private val router: ProviderRequestRouter,
    private val protocolFactory: (ResolvedProviderModel) -> LlmProtocol = { resolved ->
        val config = resolved.config
        ProtocolFactory.create(
            type = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = resolved.remoteModelId,
            serviceAccountJson = config.serviceAccountJson,
            projectId = config.projectId,
            location = config.location,
        )
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun extract(modelId: String?, systemPrompt: String, text: String, timeoutMs: Long): ExtractionResult =
        withContext(dispatcher) {
            try {
                withTimeoutOrNull(timeoutMs) {
                    val stableId = modelId?.takeIf { it.isNotBlank() }
                        ?: return@withTimeoutOrNull ExtractionResult.failure(
                            KgExtractionStatus.PROVIDER_ERROR, "未配置知识图谱模型",
                        )
                    val resolved = when (val resolution = router.resolve(stableId)) {
                        is ProviderResolution.Success -> resolution.value
                        is ProviderResolution.Failure -> return@withTimeoutOrNull ExtractionResult.failure(
                            KgExtractionStatus.PROVIDER_ERROR, "知识图谱模型解析失败：${resolution.reason}",
                        )
                    }
                    val response = protocolFactory(resolved).sendPromptSync(PromptRequest(
                        messages = listOf(
                            ProtocolMessage("system", systemPrompt),
                            ProtocolMessage("user", text),
                        ),
                        model = resolved.remoteModelId,
                        temperature = 0.0,
                        stream = false,
                    ))
                    KgGraphValidation.parse(response.content)
                } ?: ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱抽取超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.net.SocketTimeoutException) {
                ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱提供商请求超时")
            } catch (failure: Exception) {
                // 同步协议把传输超时归一为此类别前缀；不回显可能含请求信息的技术消息。
                if (failure.message?.let { it.startsWith("[TIMEOUT]") || Regex("^\\[HTTP \\d{3}\\]\\[TIMEOUT\\]").containsMatchIn(it) } == true) {
                    ExtractionResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱提供商请求超时")
                } else ExtractionResult.failure(KgExtractionStatus.PROVIDER_ERROR, "知识图谱提供商请求失败")
            }
        }
}

data class KgExtractionSettings(val modelId: String?, val systemPrompt: String) {
    companion object {
        fun resolve(config: RagConfiguration, summaryModel: String?, mainModel: String?) = KgExtractionSettings(
            modelId = config.kgExtractionModel?.takeIf { it.isNotBlank() }
                ?: summaryModel?.takeIf { it.isNotBlank() }
                ?: mainModel?.takeIf { it.isNotBlank() },
            systemPrompt = config.kgExtractionPrompt?.takeIf { it.isNotBlank() } ?: GraphExtractor.DEFAULT_KG_PROMPT,
        )
    }
}
