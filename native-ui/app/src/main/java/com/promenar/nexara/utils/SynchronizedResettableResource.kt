package com.promenar.nexara.utils

/** 并发安全的可替换单例持有器；旧资源停止完成前不会发布或构造新实例。 */
class SynchronizedResettableResource<T>(
    private val dispose: (T) -> Unit,
) {
    @Volatile
    private var value: T? = null
    @Volatile
    private var resetLatch: java.util.concurrent.CountDownLatch? = null
    private val observers = mutableListOf<ObserverEntry<T>>()

    fun getOrCreate(factory: () -> T): T {
        while (true) {
            value?.let { return it }
            var resolved: T? = null
            var observersToNotify: List<ObserverEntry<T>> = emptyList()
            val waiting = synchronized(this) {
                resetLatch?.let { return@synchronized it }
                value?.let {
                    resolved = it
                    return@synchronized null
                }
                resolved = factory().also { created ->
                    value = created
                    observersToNotify = observers.toList()
                }
                null
            }
            if (waiting != null) {
                waiting.await()
                continue
            }
            val current = checkNotNull(resolved)
            observersToNotify.forEach { entry ->
                entry.invokeIfActive(current)
            }
            return current
        }
    }

    /** 每次发布新资源实例时通知；回调始终在资源锁外执行，允许回调安全读取该实例。 */
    fun observeInstances(observer: (T) -> Unit): AutoCloseable {
        var added = false
        lateinit var entry: ObserverEntry<T>
        val current = synchronized(this) {
            entry = observers.firstOrNull { it.observer == observer }
                ?: ObserverEntry(observer).also {
                    observers += it
                    added = true
                }
            value.takeIf { added }
        }
        current?.let { instance ->
            entry.invokeIfActive(instance)
        }
        return AutoCloseable {
            if (added) {
                entry.closeAndAwait()
                synchronized(this) { observers.remove(entry) }
            }
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

    private class ObserverEntry<T>(
        val observer: (T) -> Unit,
    ) {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val idle = lock.newCondition()
        private var active = true
        private var inFlight = 0
        private val invokingThreads = java.util.IdentityHashMap<Thread, Int>()

        fun invokeIfActive(value: T) {
            val thread = Thread.currentThread()
            lock.lock()
            try {
                if (!active) return
                inFlight += 1
                invokingThreads[thread] = (invokingThreads[thread] ?: 0) + 1
            } finally {
                lock.unlock()
            }
            try {
                runCatching { observer(value) }
            } finally {
                lock.lock()
                try {
                    inFlight -= 1
                    val depth = checkNotNull(invokingThreads[thread]) - 1
                    if (depth == 0) invokingThreads.remove(thread) else invokingThreads[thread] = depth
                    idle.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        }

        /**
         * 先线性化为 inactive，再等待已进入回调退出。若观察者在自身回调中关闭，
         * 只跳过对当前线程自身深度的等待，仍会等待其它线程中的并发回调。
         */
        fun closeAndAwait() {
            val thread = Thread.currentThread()
            lock.lock()
            try {
                active = false
                val selfDepth = invokingThreads[thread] ?: 0
                while (inFlight > selfDepth) idle.await()
            } finally {
                lock.unlock()
            }
        }
    }
}
