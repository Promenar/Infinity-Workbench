package com.promenar.nexara.data.generation

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
    val modelId: String? = null,
)

internal sealed interface ChatRoutePreparation<out T> {
    data class Success<T>(val route: ChatProviderRoute, val context: T) : ChatRoutePreparation<T>
    data class Failure(val failure: ProviderResolution.Failure) : ChatRoutePreparation<Nothing> {
        override fun toString(): String = "ChatRoutePreparation.Failure(reason=${failure.reason})"
    }
}

internal class ChatProviderRouteGate(
    private val router: ProviderRequestRouter,
    private val resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun resolve(modelId: String): ChatProviderRoute = when (val resolution = router.resolve(modelId)) {
        is ProviderResolution.Failure -> ChatProviderRoute(null, null, resolution)
        is ProviderResolution.Success -> {
            val resolved = resolution.value
            val local = resolved.config.protocolType is ProtocolType.Local
            ChatProviderRoute(
                client = if (local) null else router.createClient(resolved),
                remoteModelId = resolved.remoteModelId,
                failure = null,
                useLocalProvider = local,
                modelId = resolved.modelId,
            )
        }
    }

    suspend fun <T> prepare(modelId: String, buildContext: suspend () -> T): ChatRoutePreparation<T> {
        val route = withContext(resolutionDispatcher) { resolve(modelId) }
        route.failure?.let { return ChatRoutePreparation.Failure(it) }
        return ChatRoutePreparation.Success(route, buildContext())
    }
}
