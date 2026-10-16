package com.promenar.nexara.domain.script

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class ScriptCapabilityDispatcherTest {
    @Test fun `入口读取阻塞时调用者仍及时超时且不投递迟到结果`() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val dispatcher = ScriptCapabilityDispatcher(1)
        try {
            val pending = async {
                withTimeoutOrNull(300) { dispatcher.call { entered.countDown(); release.await(5, TimeUnit.SECONDS); "迟到内容" } }
            }
            withContext(Dispatchers.IO) { check(entered.await(1, TimeUnit.SECONDS)) }
            assertThat(withTimeout(1500) { pending.await() }).isNull()
        } finally { release.countDown() }
    }

    @Test fun `阻塞能力不拖住超时且残留工作占用有界槽位`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val deliveries = AtomicInteger()
        val dispatcher = ScriptCapabilityDispatcher(1)
        val process = object : ScriptProcess {
            var terminated = false
            override val alive get() = !terminated
            override fun terminate() { terminated = true }
            override suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult {
                val invocation = dispatcher.open(capabilities)
                val result = CompletableDeferred<ScriptResult>()
                invocation.submit("read", JsonObject(emptyMap())) {
                    deliveries.incrementAndGet()
                    result.complete(ScriptResult())
                }
                return try { result.await() } finally { invocation.close() }
            }
        }
        val host = ScriptCapabilities { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            JsonNull
        }
        try {
            val runtime = SupervisedScriptRuntime { process }
            val pending = async { runtime.execute("s", ScriptRequest("1", timeoutSeconds = 1), host) }
            withContext(Dispatchers.IO) { check(entered.await(2, TimeUnit.SECONDS)) }
            val result = withTimeout(2000) { pending.await() }
            assertThat(result.errorCode).isEqualTo("TIMEOUT")
            assertThat(process.alive).isFalse()
            val blocked = dispatcher.open(host)
            blocked.submit("read", JsonObject(emptyMap())) { deliveries.incrementAndGet() }
            delay(100)
            assertThat(calls.get()).isEqualTo(1)
            blocked.close()
        } finally { release.countDown() }
        delay(100)
        assertThat(deliveries.get()).isEqualTo(0)
    }
}
