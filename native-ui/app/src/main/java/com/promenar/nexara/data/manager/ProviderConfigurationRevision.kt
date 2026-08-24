package com.promenar.nexara.data.manager

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 跨 SharedPreferences 与 SecretStore 的 Provider 配置写入序列。
 * 偶数表示稳定快照，奇数表示写入进行中；值只存在于进程内，不进入备份偏好。
 */
object ProviderConfigurationRevision {
    private val revision = AtomicLong(0L)
    private val mutationLock = ReentrantLock()

    fun current(): Long = revision.get()

    internal fun <T> publishMutation(
        afterWriteStarted: () -> Unit = {},
        block: () -> T,
    ): T = mutationLock.withLock {
        check(revision.incrementAndGet() % 2L == 1L)
        try {
            afterWriteStarted()
            block()
        } finally {
            check(revision.incrementAndGet() % 2L == 0L)
        }
    }
}
