package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun `实例观察者去重且关闭后不再接收替换实例`() {
        val resource = SynchronizedResettableResource<Int> { }
        val observed = mutableListOf<Int>()
        val observer: (Int) -> Unit = { observed += it }
        resource.getOrCreate { 1 }

        val subscription = resource.observeInstances(observer)
        val duplicate = resource.observeInstances(observer)
        resource.reset()
        resource.getOrCreate { 2 }

        assertThat(observed).containsExactly(1, 2).inOrder()

        duplicate.close()
        resource.reset()
        resource.getOrCreate { 3 }
        assertThat(observed).containsExactly(1, 2, 3).inOrder()

        subscription.close()
        resource.reset()
        resource.getOrCreate { 4 }
        assertThat(observed).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `关闭已被创建线程快照的观察者不会收到新实例`() = runBlocking {
        val firstObserverEntered = CountDownLatch(1)
        val releaseFirstObserver = CountDownLatch(1)
        val targetCalls = AtomicInteger()
        val resource = SynchronizedResettableResource<Int> { }
        resource.observeInstances {
            firstObserverEntered.countDown()
            check(releaseFirstObserver.await(5, TimeUnit.SECONDS))
        }
        val targetSubscription = resource.observeInstances { targetCalls.incrementAndGet() }

        val creating = async(Dispatchers.Default) { resource.getOrCreate { 1 } }
        assertThat(firstObserverEntered.await(5, TimeUnit.SECONDS)).isTrue()
        targetSubscription.close()
        releaseFirstObserver.countDown()

        assertThat(creating.await()).isEqualTo(1)
        assertThat(targetCalls.get()).isEqualTo(0)
    }

    @Test
    fun `close会等待已进入的回调退出且返回后不再悬挂旧回调`() = runBlocking {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeReturned = AtomicBoolean(false)
        val resource = SynchronizedResettableResource<Int> { }
        val subscription = resource.observeInstances {
            callbackEntered.countDown()
            check(releaseCallback.await(5, TimeUnit.SECONDS))
        }

        val creating = async(Dispatchers.Default) { resource.getOrCreate { 1 } }
        assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue()
        val closing = async(Dispatchers.Default) {
            closeStarted.countDown()
            subscription.close()
            closeReturned.set(true)
        }

        assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(50)
        assertThat(closeReturned.get()).isFalse()
        releaseCallback.countDown()
        closing.await()
        assertThat(creating.await()).isEqualTo(1)
        assertThat(closeReturned.get()).isTrue()
    }

    @Test
    fun `观察者可在自身回调中关闭且不会死锁或接收后续实例`() {
        val calls = AtomicInteger()
        val resource = SynchronizedResettableResource<Int> { }
        lateinit var subscription: AutoCloseable
        subscription = resource.observeInstances {
            calls.incrementAndGet()
            subscription.close()
        }

        resource.getOrCreate { 1 }
        resource.reset()
        resource.getOrCreate { 2 }

        assertThat(calls.get()).isEqualTo(1)
    }
}
