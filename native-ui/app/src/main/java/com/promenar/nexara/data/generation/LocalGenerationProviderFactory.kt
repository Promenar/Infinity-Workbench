package com.promenar.nexara.data.generation

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 本地模型的父会话、摘要与子代理共享同一个原生上下文，调用必须串行。 */
internal class LocalGenerationProviderFactory(private val provider: (String) -> LlmProvider) {
    private val mutex = Mutex()

    fun create(modelId: String): LlmProvider {
        val delegate = provider(modelId).protocol
        return LlmProvider(object : LlmProtocol by delegate {
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                mutex.withLock { emitAll(delegate.sendPrompt(request)) }
            }

            override suspend fun sendPromptSync(request: PromptRequest): PromptResponse =
                mutex.withLock { delegate.sendPromptSync(request) }
        })
    }
}
