package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SynchronizedResettableResourceTest {
    @Test
    fun `并发getter只构造一个实例且reset停止旧实例后允许唯一新实例`() = runBlocking {
        val created = AtomicInteger()
        val disposed = mutableListOf<Int>()
        val resource = SynchronizedResettableResource<Int> { value -> synchronized(disposed) { disposed += value } }

        val firstWave = List(32) {
            async(Dispatchers.Default) { resource.getOrCreate { created.incrementAndGet() } }
        }.awaitAll()
        resource.reset()
        val secondWave = List(32) {
            async(Dispatchers.Default) { resource.getOrCreate { created.incrementAndGet() } }
        }.awaitAll()

        assertThat(firstWave.distinct()).containsExactly(1)
        assertThat(secondWave.distinct()).containsExactly(2)
        assertThat(disposed).containsExactly(1)
        assertThat(created.get()).isEqualTo(2)
    }

    @Test
    fun `reset与首次构造并发时等待构造完成并停止该旧实例`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = mutableListOf<Int>()
        val resource = SynchronizedResettableResource<Int> { disposed += it }
        val creating = async(Dispatchers.Default) {
            resource.getOrCreate {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                1
            }
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        val resetting = async(Dispatchers.Default) { resource.reset() }
        release.countDown()

        assertThat(creating.await()).isEqualTo(1)
        resetting.await()
        assertThat(disposed).containsExactly(1)
        assertThat(resource.getOrCreate { 2 }).isEqualTo(2)
    }
}
