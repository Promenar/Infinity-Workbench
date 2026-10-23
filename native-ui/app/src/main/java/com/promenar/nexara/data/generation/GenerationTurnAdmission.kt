package com.promenar.nexara.data.generation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 消息入库、审批决定与接续启动共用短串行区；同一协程的嵌套回调可以重入。 */
class GenerationTurnAdmission {
    private val mutex = Mutex()
    private class Lease(val owner: GenerationTurnAdmission) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
    suspend fun <T> withLock(block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease]?.owner === this) return block()
        return mutex.withLock {
            var cancellation: CancellationException? = null
            try {
                withContext(Lease(this)) {
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        cancellation = cancelled
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) {
                // 上下文退出可能返回父 Job 的取消异常；保留业务补偿附加的失败证据。
                throw cancellation ?: cancelled
            }
        }
    }
}
