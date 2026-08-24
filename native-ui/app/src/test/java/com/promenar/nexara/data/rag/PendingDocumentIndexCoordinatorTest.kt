package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class PendingDocumentIndexCoordinatorTest {
    @Test
    fun `publish调用前context已取消仍先登记精确pending再传播取消且不下游`() = runTest {
        val target = changed(hash = "pre-cancel", epoch = 25L)
        var resolverCalls = 0
        var downstreamCalls = 0
        var failure: Throwable? = null
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { downstreamCalls += 1 },
            resolveCurrentTarget = {
                resolverCalls += 1
                it
            },
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext[Job]!!.cancel()
            failure = runCatching { coordinator.publish(target) }.exceptionOrNull()
        }
        job.join()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(resolverCalls).isEqualTo(0)
        assertThat(downstreamCalls).isEqualTo(0)
        assertThat(coordinator.snapshot()).containsExactly(target)
    }

    @Test
    fun `clear提交后late pre-cancel传播取消但不再登记ghost或调用下游`() = runTest {
        val target = changed(hash = "deleted", epoch = 26L)
        var resolverCalls = 0
        var downstreamCalls = 0
        var failure: Throwable? = null
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink {
                downstreamCalls += 1
                throw IllegalStateException("hold pending")
            },
            resolveCurrentTarget = {
                resolverCalls += 1
                it
            },
        )
        runCatching { coordinator.publish(target) }
        coordinator.clearCommitted(ROOT, listOf(FILE))
        val resolverCallsBeforeLate = resolverCalls
        val downstreamCallsBeforeLate = downstreamCalls

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext[Job]!!.cancel()
            failure = runCatching { coordinator.publish(target) }.exceptionOrNull()
        }
        job.join()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(resolverCalls).isEqualTo(resolverCallsBeforeLate)
        assertThat(downstreamCalls).isEqualTo(downstreamCallsBeforeLate)
    }

    @Test
    fun `newer成功后late older pre-cancel传播取消但不回填ghost`() = runTest {
        val older = changed(hash = "older-success", epoch = 27L)
        val newer = changed(hash = "newer-success", epoch = 28L)
        val delegated = mutableListOf<FileIndexEvent>()
        var resolverCalls = 0
        var failure: Throwable? = null
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink(delegated::add),
            resolveCurrentTarget = {
                resolverCalls += 1
                it
            },
        )
        coordinator.publish(newer)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext[Job]!!.cancel()
            failure = runCatching { coordinator.publish(older) }.exceptionOrNull()
        }
        job.join()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(delegated).containsExactly(newer)
        assertThat(resolverCalls).isEqualTo(1)
    }

    @Test
    fun `newer覆盖older且后到的older不再委托`() = runTest {
        val delegated = mutableListOf<FileIndexEvent>()
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            delegated += event
            throw IllegalStateException("queue unavailable")
        })
        val older = changed(hash = "hash-1", epoch = 1L)
        val newer = changed(hash = "hash-2", epoch = 2L)

        runCatching { coordinator.publish(older) }
        runCatching { coordinator.publish(newer) }
        coordinator.publish(older)

        assertThat(coordinator.snapshot()).containsExactly(newer)
        assertThat(delegated).containsExactly(older, newer).inOrder()
    }

    @Test
    fun `同epoch同hash幂等而异hash安全拒绝`() = runTest {
        var attempts = 0
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink {
            attempts += 1
            throw IllegalStateException("queue unavailable")
        })
        val target = changed(hash = "same", epoch = 3L)

        runCatching { coordinator.publish(target) }
        runCatching { coordinator.publish(target.copy(activeTaskId = "retry")) }
        val conflict = runCatching {
            coordinator.publish(target.copy(contentHash = "conflict"))
        }.exceptionOrNull()

        assertThat(attempts).isEqualTo(2)
        assertThat(conflict).isInstanceOf(DocumentIndexTargetConflictException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)
    }

    @Test
    fun `成功只移除精确hash与epoch且不误删并发newer`() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val older = changed(hash = "old", epoch = 4L)
        val newer = changed(hash = "new", epoch = 5L)
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            when (event) {
                older -> {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                }
                newer -> throw IllegalStateException("newer remains pending")
                else -> error("unexpected event")
            }
        })

        val oldPublish = async { coordinator.publish(older) }
        oldStarted.await()
        runCatching { coordinator.publish(newer) }
        releaseOld.complete(Unit)
        oldPublish.await()

        assertThat(coordinator.pendingTargets.value).containsExactly(newer)
    }

    @Test
    fun `取消异常重抛且完整目标保留`() = runTest {
        val target = changed(hash = "cancel", epoch = 6L, kgStrategy = "full")
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink {
            throw CancellationException("cancel publish")
        })

        val failure = runCatching { coordinator.publish(target) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.pendingTargets.value).containsExactly(target)
    }

    @Test
    fun `Deleted下游成功后才清目标`() = runTest {
        var failChanged = true
        var pendingWasPresentDuringDelete = false
        lateinit var coordinator: PendingDocumentIndexCoordinator
        coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            if (event is FileIndexEvent.Changed && failChanged) {
                throw IllegalStateException("queue unavailable")
            }
            if (event is FileIndexEvent.Deleted) {
                pendingWasPresentDuringDelete = coordinator.snapshot().isNotEmpty()
            }
        })
        val target = changed(hash = "delete", epoch = 7L)
        runCatching { coordinator.publish(target) }
        failChanged = false

        coordinator.publish(FileIndexEvent.Deleted(ROOT, FILE))

        assertThat(pendingWasPresentDuringDelete).isTrue()
        assertThat(coordinator.snapshot()).isEmpty()
    }

    @Test
    fun `Deleted下游普通失败与取消均保留目标且取消重抛`() = runTest {
        val target = changed(hash = "delete-failure", epoch = 11L)
        var deleteFailure: Exception = IllegalStateException("delete failed")
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            if (event is FileIndexEvent.Changed) throw IllegalStateException("register pending")
            throw deleteFailure
        })
        runCatching { coordinator.publish(target) }

        val ordinary = runCatching {
            coordinator.publish(FileIndexEvent.Deleted(ROOT, FILE))
        }.exceptionOrNull()
        assertThat(ordinary).isInstanceOf(IllegalStateException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)

        deleteFailure = CancellationException("cancel delete")
        val cancelled = runCatching {
            coordinator.publish(FileIndexEvent.Deleted(ROOT, FILE))
        }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)
    }

    @Test
    fun `clearCommitted只清指定workspace与文件且不调用下游`() = runTest {
        val delegated = mutableListOf<FileIndexEvent>()
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            delegated += event
            throw IllegalStateException("register pending")
        })
        val first = changed(hash = "first", epoch = 12L)
        val second = changed(hash = "second", epoch = 13L).copy(fileUuid = "second")
        val otherRoot = changed(hash = "other", epoch = 14L).copy(workspaceRootUuid = "other-root")
        listOf(first, second, otherRoot).forEach { runCatching { coordinator.publish(it) } }
        val delegatedBeforeClear = delegated.toList()

        coordinator.clearCommitted(ROOT, listOf(FILE, "second", "missing"))

        assertThat(coordinator.snapshot()).containsExactly(otherRoot)
        assertThat(delegated).containsExactlyElementsIn(delegatedBeforeClear).inOrder()
    }

    @Test
    fun `回收清理pending但不永久封禁uuid恢复后可登记精确新目标`() = runTest {
        val delegated = mutableListOf<FileIndexEvent>()
        var fail = true
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            delegated += event
            if (fail) throw IllegalStateException("hold pending")
        })
        val beforeRecycle = changed(hash = "before", epoch = 31L)
        runCatching { coordinator.publish(beforeRecycle) }

        coordinator.clearForRecycle(ROOT, listOf(FILE))

        assertThat(coordinator.snapshot()).isEmpty()
        fail = false
        val restored = changed(hash = "restored", epoch = 32L)
        coordinator.publish(restored)
        assertThat(delegated).containsExactly(beforeRecycle, restored).inOrder()
    }

    @Test
    fun `retry普通失败返回false成功精确移除且不会删除期间覆盖的newer`() = runTest {
        val older = changed(hash = "retry-old", epoch = 8L)
        val newer = changed(hash = "retry-new", epoch = 9L)
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var phase = "register"
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink { event ->
            when (phase) {
                "register" -> throw IllegalStateException("queue unavailable")
                "retry-fail" -> throw IllegalStateException("retry failed")
                "retry-block" -> if (event == older) {
                    retryStarted.complete(Unit)
                    releaseRetry.await()
                } else {
                    throw IllegalStateException("newer remains pending")
                }
            }
        })
        runCatching { coordinator.publish(older) }

        phase = "retry-fail"
        assertThat(coordinator.retry(older)).isFalse()
        assertThat(coordinator.snapshot()).containsExactly(older)

        phase = "retry-block"
        val retry = async { coordinator.retry(older) }
        retryStarted.await()
        runCatching { coordinator.publish(newer) }
        releaseRetry.complete(Unit)

        assertThat(retry.await()).isTrue()
        assertThat(coordinator.snapshot()).containsExactly(newer)
    }

    @Test
    fun `retry取消异常重抛并保留目标`() = runTest {
        val target = changed(hash = "retry-cancel", epoch = 10L)
        var cancel = false
        val coordinator = PendingDocumentIndexCoordinator(FileIndexEventSink {
            if (cancel) throw CancellationException("cancel retry")
            throw IllegalStateException("register pending")
        })
        runCatching { coordinator.publish(target) }
        cancel = true

        val failure = runCatching { coordinator.retry(target) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)
    }

    @Test
    fun `首次resolve普通异常与取消均传播且请求已保留pending`() = runTest {
        var failure: Exception = IllegalStateException("resolve failed")
        var downstreamCalls = 0
        val target = changed(hash = "resolve-failure", epoch = 15L)
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { downstreamCalls += 1 },
            resolveCurrentTarget = { throw failure },
        )

        val ordinary = runCatching { coordinator.publish(target) }.exceptionOrNull()
        assertThat(ordinary).isInstanceOf(IllegalStateException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)

        failure = CancellationException("resolve cancelled")
        val cancelled = runCatching { coordinator.publish(target) }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)
        assertThat(downstreamCalls).isEqualTo(0)
    }

    @Test
    fun `删除清理完成后迟到Changed经resolver确认已删并清理`() = runTest {
        var resolved: FileIndexEvent.Changed? = null
        val delegated = mutableListOf<FileIndexEvent>()
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { event ->
                delegated += event
                throw IllegalStateException("hold pending")
            },
            resolveCurrentTarget = { resolved },
        )
        val target = changed(hash = "late", epoch = 16L)
        resolved = target
        runCatching { coordinator.publish(target) }
        resolved = null
        coordinator.clearCommitted(ROOT, listOf(FILE))

        coordinator.publish(target)

        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(delegated).containsExactly(target)
    }

    @Test
    fun `register解析与clear在同一mutex线性化且最终无pending`() = runTest {
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val target = changed(hash = "linearized", epoch = 17L)
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { throw IllegalStateException("hold pending") },
            resolveCurrentTarget = {
                resolverStarted.complete(Unit)
                releaseResolver.await()
                it
            },
        )

        val register = async { runCatching { coordinator.publish(target) } }
        resolverStarted.await()
        val clear = async { coordinator.clearCommitted(ROOT, listOf(FILE)) }
        yield()
        assertThat(clear.isCompleted).isFalse()
        releaseResolver.complete(Unit)
        register.await()
        clear.await()

        assertThat(coordinator.snapshot()).isEmpty()
    }

    @Test
    fun `当前target正常登记委托并在成功后精确移除`() = runTest {
        val target = changed(hash = "current", epoch = 18L)
        val delegated = mutableListOf<FileIndexEvent>()
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink(delegated::add),
            resolveCurrentTarget = { it },
        )

        coordinator.publish(target)

        assertThat(delegated).containsExactly(target)
        assertThat(coordinator.snapshot()).isEmpty()
    }

    @Test
    fun `publish resolver返回newer时在下游前升级pending并下发resolved`() = runTest {
        val requested = changed(hash = "publish-old", epoch = 23L, kgStrategy = "full")
        val resolved = requested.copy(contentHash = "publish-new", targetEpoch = 24L)
        val delegated = mutableListOf<FileIndexEvent.Changed>()
        lateinit var coordinator: PendingDocumentIndexCoordinator
        coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { event ->
                event as FileIndexEvent.Changed
                assertThat(coordinator.snapshot()).containsExactly(resolved)
                delegated += event
            },
            resolveCurrentTarget = { resolved },
        )

        coordinator.publish(requested)

        assertThat(delegated).containsExactly(resolved)
        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(resolved.kgStrategy).isEqualTo("full")
    }

    @Test
    fun `retry resolver普通异常返回false取消传播且均保留pending`() = runTest {
        val target = changed(hash = "retry-resolve-failure", epoch = 19L)
        var resolverFailure: Exception? = null
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { throw IllegalStateException("hold pending") },
            resolveCurrentTarget = {
                resolverFailure?.let { throw it }
                it
            },
        )
        runCatching { coordinator.publish(target) }

        resolverFailure = IllegalStateException("retry resolve failed")
        assertThat(coordinator.retry(target)).isFalse()
        assertThat(coordinator.snapshot()).containsExactly(target)

        resolverFailure = CancellationException("retry resolve cancelled")
        val cancelled = runCatching { coordinator.retry(target) }.exceptionOrNull()
        assertThat(cancelled).isInstanceOf(CancellationException::class.java)
        assertThat(coordinator.snapshot()).containsExactly(target)
    }

    @Test
    fun `retry resolver确认已删除时清理pending且不调用下游`() = runTest {
        val target = changed(hash = "retry-deleted", epoch = 20L)
        var resolved: FileIndexEvent.Changed? = target
        var downstreamCalls = 0
        val coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink {
                downstreamCalls += 1
                throw IllegalStateException("hold pending")
            },
            resolveCurrentTarget = { resolved },
        )
        runCatching { coordinator.publish(target) }
        resolved = null

        assertThat(coordinator.retry(target)).isTrue()
        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(downstreamCalls).isEqualTo(1)
    }

    @Test
    fun `retry resolver返回newer时升级pending并下发更新目标`() = runTest {
        val older = changed(hash = "retry-old-resolved", epoch = 21L, kgStrategy = "full")
        val newer = older.copy(contentHash = "retry-new-resolved", targetEpoch = 22L)
        var resolved = older
        val delegated = mutableListOf<FileIndexEvent.Changed>()
        lateinit var coordinator: PendingDocumentIndexCoordinator
        coordinator = PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { event ->
                event as FileIndexEvent.Changed
                delegated += event
                if (event == older) throw IllegalStateException("hold pending")
                assertThat(coordinator.snapshot()).containsExactly(newer)
            },
            resolveCurrentTarget = { resolved },
        )
        runCatching { coordinator.publish(older) }
        resolved = newer

        assertThat(coordinator.retry(older)).isTrue()
        assertThat(delegated).containsExactly(older, newer).inOrder()
        assertThat(coordinator.snapshot()).isEmpty()
        assertThat(newer.kgStrategy).isEqualTo("full")
    }

    private fun changed(
        hash: String,
        epoch: Long,
        kgStrategy: String? = null,
    ) = FileIndexEvent.Changed(
        workspaceRootUuid = ROOT,
        fileUuid = FILE,
        contentHash = hash,
        targetEpoch = epoch,
        kgStrategy = kgStrategy,
    )

    private companion object {
        const val ROOT = "root"
        const val FILE = "file"
    }
}
