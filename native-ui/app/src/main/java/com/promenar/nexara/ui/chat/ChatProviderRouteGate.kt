package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.protocol.ProtocolType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ChatProviderRoute(
    val client: UnifiedLlmClient?,
    val remoteModelId: String?,
    val failure: ProviderResolution.Failure?,
    val useLocalProvider: Boolean = false,
)

internal sealed interface ChatRoutePreparation<out T> {
    data class Success<T>(val route: ChatProviderRoute, val context: T) : ChatRoutePreparation<T>
    data class Failure(val failure: ProviderResolution.Failure) : ChatRoutePreparation<Nothing>
}

/**
 * 请求发送前的 Provider 路由门禁。
 *
 * 解析失败时不创建客户端；成功结果冻结本次请求的配置快照。
 */
internal class ChatProviderRouteGate(
    private val router: ProviderRequestRouter,
    private val resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun resolve(modelId: String): ChatProviderRoute = when (val resolution = router.resolve(modelId)) {
        is ProviderResolution.Failure -> ChatProviderRoute(
            client = null,
            remoteModelId = null,
            failure = resolution,
        )
        is ProviderResolution.Success -> {
            val resolved = resolution.value
            val isLocal = resolved.config.protocolType is ProtocolType.Local
            ChatProviderRoute(
                client = if (isLocal) null else router.createClient(resolved),
                remoteModelId = resolved.remoteModelId,
                failure = null,
                useLocalProvider = isLocal,
            )
        }
    }

    /** 结构性保证：路由失败时，Context/RAG/WebSearch 构建回调不会被调用。 */
    suspend fun <T> prepare(
        modelId: String,
        buildContext: suspend () -> T,
    ): ChatRoutePreparation<T> {
        val route = withContext(resolutionDispatcher) { resolve(modelId) }
        route.failure?.let { return ChatRoutePreparation.Failure(it) }
        return ChatRoutePreparation.Success(route, buildContext())
    }
}
