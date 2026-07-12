package com.promenar.nexara.utils

/** 并发安全的可替换单例持有器；旧资源停止完成前不会发布或构造新实例。 */
class SynchronizedResettableResource<T>(
    private val dispose: (T) -> Unit,
) {
    @Volatile
    private var value: T? = null
    @Volatile
    private var resetLatch: java.util.concurrent.CountDownLatch? = null

    fun getOrCreate(factory: () -> T): T {
        while (true) {
            value?.let { return it }
            val waiting = synchronized(this) {
                resetLatch?.let { return@synchronized it }
                value?.let { return it }
                return factory().also { value = it }
            }
            waiting.await()
        }
    }

    fun peek(): T? = value

    fun reset() {
        val handle = beginReset() ?: return
        try {
            dispose(handle.value)
        } finally {
            completeReset(handle)
        }
    }

    fun beginReset(): ResetHandle<T>? = synchronized(this) {
        if (resetLatch != null) return null
        val current = value ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        resetLatch = latch
        value = null
        ResetHandle(current, latch)
    }

    fun completeReset(handle: ResetHandle<T>) = synchronized(this) {
        if (resetLatch !== handle.latch) return@synchronized
        resetLatch = null
        handle.latch.countDown()
    }

    class ResetHandle<T> internal constructor(
        val value: T,
        internal val latch: java.util.concurrent.CountDownLatch,
    )
}
