package com.promenar.infinity.data.termux

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 等待记录仅属于当前进程；完成、超时或取消后不再接受该请求的结果。 */
class TermuxRequestRegistry {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    suspend fun execute(requestId: String, timeoutMillis: Long, dispatch: () -> Unit, cleanup: () -> Unit = {}): TermuxCommandResult {
        require(requestId.isNotBlank() && timeoutMillis > 0)
        val deferred = CompletableDeferred<TermuxCommandResult>()
        check(pending.putIfAbsent(requestId, deferred) == null) { "Termux 请求标识重复" }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                dispatch()
                deferred.await()
            } ?: TermuxCommandResult(
                errorCode = "TERMUX_TIMEOUT", timedOut = true,
                guidance = "本地等待已超时；命令可能仍在运行。RUN_COMMAND 没有按请求停止后台命令的公开接口，请在 Termux 中检查并停止对应进程；也请检查 allow-external-apps 开关和后台进程限制。",
            )
        } finally {
            pending.remove(requestId, deferred)
            deferred.cancel()
            cleanup()
        }
    }

    fun complete(requestId: String, result: TermuxCommandResult): Boolean = pending[requestId]?.complete(result) == true
}
