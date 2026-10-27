package com.promenar.nexara.data.local.inference

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.LocalGenerationProviderFactory
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.provider.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalInferenceEngineConcurrencyTest {
    @Test
    fun `设置直接Provider与生成工厂共用引擎互斥`() = runTest {
        val fixture = fixture()
        val factory = LocalGenerationProviderFactory { model -> LlmProvider.local(fixture.engine, model) }
        val parent = backgroundScope.launch {
            factory.create("parent").sendPrompt(request("parent")).collect {}
        }
        runCurrent()

        val settings = backgroundScope.launch {
            LlmProvider.local(fixture.engine, "settings").sendPromptSync(request("settings"))
        }
        runCurrent()

        assertThat(fixture.main.generationPrompts).hasSize(1)
        parent.cancel()
        runCurrent()
        assertThat(fixture.main.generationPrompts).hasSize(2)
        settings.cancel()
    }

    @Test
    fun `取消生成后等待底层消费退出才允许下一次生成`() = runTest {
        val nativeExit = CompletableDeferred<Unit>()
        val fixture = fixture(main = ControlledBackend(nativeExit = nativeExit))
        val first = backgroundScope.launch { fixture.engine.generate("first").collect {} }
        runCurrent()

        try {
            first.cancel()
            runCurrent()
            assertThat(fixture.main.generationClosing.isCompleted).isTrue()
            assertThat(first.isCompleted).isFalse()

            val second = backgroundScope.launch { fixture.engine.generate("second").collect {} }
            runCurrent()
            assertThat(fixture.main.generationPrompts).containsExactly("first")

            nativeExit.complete(Unit)
            runCurrent()
            assertThat(first.isCompleted).isTrue()
            assertThat(fixture.main.generationPrompts).containsExactly("first", "second").inOrder()
            second.cancel()
        } finally {
            nativeExit.complete(Unit)
        }
    }

    @Test
    fun `生成持有模型时加载和卸载等待同一边界`() = runTest {
        val generationFinish = CompletableDeferred<Unit>()
        val fixture = fixture(main = ControlledBackend(generationFinish = generationFinish))
        val generation = backgroundScope.launch { fixture.engine.generate("first").collect {} }
        runCurrent()

        try {
            val unload = backgroundScope.launch { fixture.engine.unloadModel(SlotType.MAIN) }
            val reload = backgroundScope.launch {
                fixture.engine.loadModel(SlotType.MAIN, "second-model.gguf", loadConfig).getOrThrow()
            }
            runCurrent()
            assertThat(fixture.main.releaseCalls).isEqualTo(0)
            assertThat(fixture.main.loadedPaths).containsExactly("first-model.gguf")
            assertThat(unload.isCompleted).isFalse()
            assertThat(reload.isCompleted).isFalse()

            generationFinish.complete(Unit)
            runCurrent()
            assertThat(generation.isCompleted).isTrue()
            assertThat(unload.isCompleted).isTrue()
            assertThat(reload.isCompleted).isTrue()
            assertThat(fixture.main.releaseCalls).isEqualTo(1)
            assertThat(fixture.main.loadedPaths)
                .containsExactly("first-model.gguf", "second-model.gguf").inOrder()
            assertThat(fixture.engine.mainSlot.value.modelPath).isEqualTo("second-model.gguf")
            assertThat(fixture.engine.mainSlot.value.isLoaded).isTrue()
        } finally {
            generationFinish.complete(Unit)
        }
    }

    @Test
    fun `排队调用取消不会中断持锁生成也不会进入后端`() = runTest {
        val fixture = fixture()
        val first = backgroundScope.launch { fixture.engine.generate("first").collect {} }
        runCurrent()
        val waiting = backgroundScope.launch { fixture.engine.generate("waiting").collect {} }
        runCurrent()
        waiting.cancel()
        runCurrent()

        assertThat(first.isActive).isTrue()
        assertThat(fixture.main.generationPrompts).containsExactly("first")
        first.cancel()
    }

    @Test
    fun `批量嵌入消费结束前不释放对应模型`() = runTest {
        val embeddingFinish = CompletableDeferred<Unit>()
        val fixture = fixture(embedding = ControlledBackend(embeddingFinish = embeddingFinish))
        fixture.engine.loadModel(SlotType.EMBEDDING, "embedding-model.gguf", loadConfig).getOrThrow()
        val embedded = backgroundScope.async { fixture.engine.embedBatch(listOf("one", "two")) }
        runCurrent()

        try {
            val unload = backgroundScope.launch { fixture.engine.unloadModel(SlotType.EMBEDDING) }
            runCurrent()
            assertThat(fixture.embedding.releaseCalls).isEqualTo(0)
            assertThat(unload.isCompleted).isFalse()

            embeddingFinish.complete(Unit)
            runCurrent()
            assertThat(embedded.await().getOrThrow()).hasSize(2)
            assertThat(fixture.embedding.embeddedTexts).containsExactly("one", "two").inOrder()
            assertThat(fixture.embedding.releaseCalls).isEqualTo(1)
        } finally {
            embeddingFinish.complete(Unit)
        }
    }

    @Test
    fun `关闭引擎先等待活跃消费退出再释放并拒绝新调用`() = runTest {
        val nativeExit = CompletableDeferred<Unit>()
        val fixture = fixture(main = ControlledBackend(nativeExit = nativeExit))
        val generation = backgroundScope.launch { fixture.engine.generate("first").collect {} }
        runCurrent()

        try {
            fixture.engine.release()
            runCurrent()
            assertThat(fixture.main.releaseCalls).isEqualTo(0)
            assertThat(generation.isCompleted).isFalse()

            val rejected = backgroundScope.async {
                runCatching { fixture.engine.generate("after-close").toList() }
            }
            runCurrent()
            nativeExit.complete(Unit)
            runCurrent()
            assertThat(rejected.await().isFailure).isTrue()
            assertThat(fixture.main.releaseCalls).isEqualTo(1)
            assertThat(fixture.embedding.releaseCalls).isEqualTo(1)
            assertThat(fixture.rerank.releaseCalls).isEqualTo(1)
            assertThat(fixture.engine.mainSlot.value.isLoaded).isFalse()
            assertThat(fixture.main.generationPrompts).containsExactly("first")
        } finally {
            nativeExit.complete(Unit)
        }
    }

    private suspend fun TestScope.fixture(
        main: ControlledBackend = ControlledBackend(),
        embedding: ControlledBackend = ControlledBackend(),
        rerank: ControlledBackend = ControlledBackend(),
    ): Fixture {
        val engine = LocalInferenceEngine(main, embedding, rerank, StandardTestDispatcher(testScheduler))
        engine.loadModel(SlotType.MAIN, "first-model.gguf", loadConfig).getOrThrow()
        return Fixture(engine, main, embedding, rerank)
    }

    private fun request(text: String) = PromptRequest(
        model = "fake-local-model",
        messages = listOf(ProtocolMessage(role = "user", content = text)),
    )

    private data class Fixture(
        val engine: LocalInferenceEngine,
        val main: ControlledBackend,
        val embedding: ControlledBackend,
        val rerank: ControlledBackend,
    )

    private class ControlledBackend(
        private val generationFinish: CompletableDeferred<Unit>? = null,
        private val nativeExit: CompletableDeferred<Unit>? = null,
        private val embeddingFinish: CompletableDeferred<Unit>? = null,
    ) : InferenceBackend {
        override val backendType = BackendType.LLAMA_CPU
        override var isLoaded = false
            private set
        val loadedPaths = mutableListOf<String>()
        val generationPrompts = mutableListOf<String>()
        val embeddedTexts = mutableListOf<String>()
        val generationClosing = CompletableDeferred<Unit>()
        var releaseCalls = 0
            private set

        override suspend fun loadModel(path: String, config: LoadConfig): Result<Unit> {
            loadedPaths += path
            isLoaded = true
            return Result.success(Unit)
        }

        override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
            generationPrompts += prompt
            try {
                if (generationFinish != null) {
                    generationFinish.await()
                    emit("token")
                } else {
                    awaitCancellation()
                }
            } finally {
                generationClosing.complete(Unit)
                withContext(NonCancellable) { nativeExit?.await() }
            }
        }

        override suspend fun embed(text: String): Result<FloatArray> {
            embeddedTexts += text
            embeddingFinish?.await()
            return if (isLoaded) Result.success(floatArrayOf(1f, 0f))
            else Result.failure(IllegalStateException("model released during embedding"))
        }

        override fun release() {
            releaseCalls += 1
            isLoaded = false
        }
    }

    private companion object {
        val loadConfig = LoadConfig(threadCount = 1)
    }
}
