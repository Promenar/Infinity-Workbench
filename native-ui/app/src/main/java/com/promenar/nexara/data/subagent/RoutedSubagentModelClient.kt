package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.generation.ChatProviderRouteGate
import com.promenar.nexara.data.generation.ChatRoutePreparation
import com.promenar.nexara.data.model.params.ReasoningEffort
import com.promenar.nexara.data.remote.StreamConfig
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 子代理与父会话走同一模型的精确路由，不使用默认提供商兜底。 */
internal class RoutedSubagentModelClient(
    private val routeGate: ChatProviderRouteGate,
    private val localProviderFactory: (String) -> LlmProvider,
) : SubagentModelClient {
    override suspend fun stream(
        modelId: String,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>,
        maxOutputTokens: Int,
        reasoningEffort: ReasoningEffort?,
    ): Flow<StreamChunk> {
        val prepared = routeGate.prepareWithRoute(modelId) { it }
        if (prepared !is ChatRoutePreparation.Success) throw SubagentModelUnavailable("会话模型当前不可用")
        val route = prepared.route
        val remoteModelId = route.remoteModelId?.takeIf(String::isNotBlank) ?: throw SubagentModelUnavailable("会话模型当前不可用")
        val usable = if (route.supportsToolCalls) tools else emptyList()
        val choice = if (usable.isEmpty()) ProtocolToolChoice.NONE else ProtocolToolChoice.AUTO
        if (route.useLocalProvider) {
            val provider = localProviderFactory(remoteModelId)
            // 本地推理共用一个原生上下文且没有内部锁，并行子代理必须逐个占用。
            val request = PromptRequest(
                messages = messages,
                model = remoteModelId,
                maxTokens = maxOutputTokens,
                reasoningEffort = reasoningEffort,
                tools = usable.takeIf { it.isNotEmpty() },
                toolChoice = choice,
                stream = true,
                webSearch = false,
                enableGeminiSearch = false,
            )
            return flow { localInference.withLock { emitAll(provider.sendPrompt(request)) } }
        }
        val client = route.client ?: throw SubagentModelUnavailable("会话模型当前不可用")
        return client.sendStream(
            StreamTextParams(
                messages = messages,
                model = remoteModelId,
                maxOutputTokens = maxOutputTokens,
                reasoningEffort = reasoningEffort,
                tools = usable.associateBy { it.function.name },
                toolChoice = choice,
                enableWebSearch = false,
                enableGeminiSearch = false,
            ),
            StreamConfig(),
        )
    }

    private val localInference = Mutex()
}
