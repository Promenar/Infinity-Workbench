package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeferredPreferenceChangeListenerTest {
    /**
     * 复现删除提供商时的锁顺序反转：写入方持提供商锁 apply()，监听器重建组件要拿抽取器锁；
     * 后台线程正持抽取器锁并反过来读取提供商配置。监听器推迟执行后，写入方先释放锁，三方都能完成。
     */
    @Test
    fun `写入方持锁触发回调时回调推迟到锁外执行不会与反向加锁的线程死锁`() {
        val providerLock = Any()
        val extractorLock = Any()
        val mainLoop = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val rebuilt = CountDownLatch(1)
        val listener = DeferredPreferenceChangeListener(post = { mainLoop.execute(it) }) { key ->
            assertThat(key).isEqualTo("all_models")
            synchronized(extractorLock) { rebuilt.countDown() }
        }
        val extractorHeld = CountDownLatch(1)
        val providerHeld = CountDownLatch(1)
        val worker = Thread {
            synchronized(extractorLock) {
                extractorHeld.countDown()
                providerHeld.await()
                synchronized(providerLock) {}
            }
        }.apply { isDaemon = true; start() }
        val writer = Thread {
            extractorHeld.await()
            synchronized(providerLock) {
                providerHeld.countDown()
                listener.onSharedPreferenceChanged(null, "all_models")
            }
        }.apply { isDaemon = true; start() }

        writer.join(2_000)
        worker.join(2_000)
        assertThat(writer.isAlive).isFalse()
        assertThat(worker.isAlive).isFalse()
        assertThat(rebuilt.await(2, TimeUnit.SECONDS)).isTrue()
        mainLoop.shutdownNow()
    }
}
