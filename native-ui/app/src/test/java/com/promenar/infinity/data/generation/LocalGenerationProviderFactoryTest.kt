package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.data.remote.provider.LlmProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalGenerationProviderFactoryTest {
    @Test fun `父会话与子代理共用本地推理锁且取消后让出`() = runTest {
        val entered = mutableListOf<String>()
        val factory = LocalGenerationProviderFactory { _ ->
            LlmProvider(object : LlmProtocol {
                override val protocolType = ProtocolType.Local
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                    entered += request.model
                    awaitCancellation()
                }
                override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse(content = "")
                override fun cancel() = Unit
            })
        }
        val first = launch { factory.create("parent").sendPrompt(PromptRequest(model = "parent", messages = emptyList())).collect {} }
        runCurrent()
        val second = launch { factory.create("child").sendPrompt(PromptRequest(model = "child", messages = emptyList())).collect {} }
        runCurrent()
        assertThat(entered).containsExactly("parent")
        first.cancel()
        runCurrent()
        assertThat(entered).containsExactly("parent", "child").inOrder()
        second.cancel()
    }
}
