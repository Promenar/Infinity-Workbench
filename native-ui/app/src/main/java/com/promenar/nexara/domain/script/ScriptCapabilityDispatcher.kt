package com.promenar.nexara.domain.script

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 阻塞的系统 DNS/文件提供商不能拖住 watchdog；残留操作共用有界槽位。 */
class ScriptCapabilityDispatcher(maxConcurrent: Int = 4) {
    private val permits = Semaphore(maxConcurrent)

    fun open(capabilities: ScriptCapabilities): Invocation = Invocation(capabilities)

    suspend fun <T> call(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = CompletableDeferred<T>()
        scope.launch {
            try {
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    val value = block()
                    currentCoroutineContext().ensureActive()
                    result.complete(value)
                }
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return try { result.await() } finally { scope.cancel() }
    }

    inner class Invocation internal constructor(private val capabilities: ScriptCapabilities) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun submit(operation: String, arguments: JsonObject, complete: (Result<JsonElement>) -> Unit) {
            scope.launch {
                try {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        val value = capabilities.invoke(operation, arguments)
                        currentCoroutineContext().ensureActive()
                        complete(Result.success(value))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (isActive) complete(Result.failure(failure)) }
            }
        }

        fun close() { scope.cancel() }
    }

    companion object {
        val Shared = ScriptCapabilityDispatcher()
    }
}
