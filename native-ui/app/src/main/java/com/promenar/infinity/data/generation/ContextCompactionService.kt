package com.promenar.infinity.data.generation

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContextCompactionBusyException : IllegalStateException("context_compaction_generation_active")

data class ContextCompactionProgress(
    val isRunning: Boolean = false,
    val stage: ContextCompactionStage? = null,
    val result: ContextCompactionResult? = null,
    val failed: Boolean = false,
)

/** 自动与手动压缩共享会话锁；生成准入也使用该锁，关闭手动检查与生成启动之间的竞态。 */
class ContextCompactionService(
    private val isGenerating: (String) -> Boolean,
    private val manualCompaction: suspend (String, (ContextCompactionStage) -> Unit) -> ContextCompactionResult,
    private val admission: suspend (String, suspend () -> ContextCompactionResult) -> ContextCompactionResult = { _, block -> block() },
    private val applicationScope: CoroutineScope? = null,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val progress = ConcurrentHashMap<String, MutableStateFlow<ContextCompactionProgress>>()

    fun observe(sessionId: String): StateFlow<ContextCompactionProgress> = state(sessionId)

    fun dismiss(sessionId: String) {
        val state = state(sessionId)
        val current = state.value
        if (!current.isRunning) state.compareAndSet(current, ContextCompactionProgress())
    }

    suspend fun compactNow(sessionId: String): ContextCompactionResult {
        if (isGenerating(sessionId)) throw ContextCompactionBusyException()
        return applicationScope?.async { compactAdmitted(sessionId) }?.await() ?: compactAdmitted(sessionId)
    }

    private suspend fun compactAdmitted(sessionId: String): ContextCompactionResult {
        return admission(sessionId) {
            lock(sessionId).withLock {
                if (isGenerating(sessionId)) throw ContextCompactionBusyException()
                val state = state(sessionId)
                state.value = ContextCompactionProgress(isRunning = true)
                try {
                    val result = manualCompaction(sessionId) { stage ->
                        state.value = ContextCompactionProgress(isRunning = true, stage = stage)
                    }
                    state.value = ContextCompactionProgress(
                        stage = ContextCompactionStage.COMPLETE, result = result, failed = result.summaryFailed,
                    )
                    result
                } catch (error: Throwable) {
                    state.value = ContextCompactionProgress(failed = true)
                    throw error
                }
            }
        }
    }

    suspend fun compactAutomatically(
        sessionId: String,
        operation: suspend () -> ContextCompactionResult,
    ): ContextCompactionResult = lock(sessionId).withLock { operation() }

    suspend fun <T> withGenerationStart(sessionId: String, operation: suspend () -> T): T =
        lock(sessionId).withLock { operation() }

    private fun lock(sessionId: String): Mutex = locks.getOrPut(sessionId) { Mutex() }
    private fun state(sessionId: String): MutableStateFlow<ContextCompactionProgress> =
        progress.getOrPut(sessionId) { MutableStateFlow(ContextCompactionProgress()) }
}
