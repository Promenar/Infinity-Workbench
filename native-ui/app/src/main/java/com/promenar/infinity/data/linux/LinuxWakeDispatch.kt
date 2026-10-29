package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.trigger.SystemTurnDispatcher
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 以请求发起时间计冷却；模型回复的挂起区间不阻塞收件箱和其它会话。 */
class LinuxWakeDispatch(
    private val scope: CoroutineScope,
    private val dispatcher: () -> SystemTurnDispatcher?,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = { Logger.getLogger("LinuxWake").warning(it) },
) {
    private class Session(parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        var lastDispatch: Long? = null
        val pending = linkedMapOf<String, LinuxWakeMessage>()
        var timer: Job? = null
    }
    private val gate = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    suspend fun accept(sessionId: String, message: LinuxWakeMessage) = gate.withLock {
        scope.coroutineContext.ensureActive()
        val state = sessions.getOrPut(sessionId) { Session(scope) }
        state.pending.remove(message.key)
        state.pending[message.key] = message
        if (state.pending.size > LinuxWakeLimits.MAX_PENDING_KEYS) {
            state.pending.remove(state.pending.keys.first())
            log("脚本唤醒待发 key 超限，已丢弃最旧消息")
        }
        val wait = remaining(state)
        if (wait == 0L) {
            state.timer?.cancel(); state.timer = null
            dispatch(sessionId, state)
        } else if (state.timer == null) {
            state.timer = state.scope.launch {
                delay(wait)
                gate.withLock {
                    // 计时器与新消息共享互斥边界，避免冷却到期时重复派发。
                    state.timer = null
                    if (sessions[sessionId] === state && state.pending.isNotEmpty()) dispatch(sessionId, state)
                }
            }
        }
    }

    private fun remaining(state: Session): Long = state.lastDispatch?.let {
        (LinuxWakeLimits.MIN_DISPATCH_INTERVAL - (now() - it)).coerceIn(0, LinuxWakeLimits.MIN_DISPATCH_INTERVAL)
    } ?: 0

    private fun dispatch(sessionId: String, state: Session) {
        val request = LinuxWakeRequests.compose(sessionId, state.pending.values.toList())
        state.pending.clear()
        // 在当前协程中开始请求，保证实际发起时间和冷却起点一致；首次挂起后交还应用作用域。
        state.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val target = dispatcher()
                if (target == null) log("脚本唤醒调度器不可用，已丢弃消息") else {
                    state.lastDispatch = now()
                    target.request(request)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { log("脚本唤醒调度失败") }
        }
    }

    suspend fun forget(sessionId: String) = gate.withLock { sessions.remove(sessionId)?.scope?.cancel() }
}
