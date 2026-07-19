package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coJustRun
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class VectorizationQueueRoomTest {
    private lateinit var database: NexaraDatabase
    private lateinit var source: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = File.createTempFile("nexara-queue-room", ".txt").apply { writeText("content") }
    }

    @After
    fun tearDown() {
        database.close()
        source.delete()
    }

    @Test
    fun `真实Room中的失败任务通过Update重置不会触发主键冲突`() = runTest {
        seedFileAndTask("failed")
        val queue = queue(StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)

        assertThat(id).isEqualTo(TASK)
        assertThat(database.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
        assertThat(queue.getQueueLength()).isEqualTo(1)
    }

    @Test
    fun `启动恢复会把missing scan发现的失败任务重置并重新入队`() = runTest {
        seedFileAndTask("failed")
        val queue = queue(StandardTestDispatcher(testScheduler))

        val result = queue.resumeInterruptedTasks()

        assertThat(result.isSuccess).isTrue()
        assertThat(database.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
        assertThat(queue.snapshotState().queue.map { it.id }).containsExactly(TASK)
    }

    @Test
    fun `空库冷启动会清理已删除文档遗留的失败任务`() = runTest {
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = TASK,
                type = "document",
                status = "failed",
                docTitle = source.name,
                progress = 0.0,
                error = "历史索引失败",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById(TASK)).isNull()
        assertThat(queue.snapshotState().queue).isEmpty()
        assertThat(queue.snapshotState().currentTask).isNull()
        queue.shutdown()
    }

    @Test
    fun `现代文件引用attention不会被legacy空库清理误删`() = runTest {
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = TASK,
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "failed",
                docTitle = source.name,
                progress = 0.0,
                error = "等待文件事件协调器处理",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById(TASK)).isNotNull()
        assertThat(queue.snapshotState().queue.map { it.id }).contains(TASK)
        queue.shutdown()
    }

    @Test
    fun `配置reset等待旧queue停止并把处理中任务交接给新queue恢复`() = runTest {
        seedFileAndTask("vectorizing")
        val holder = com.promenar.nexara.utils.SynchronizedResettableResource<VectorizationQueue>(
            VectorizationQueue::shutdown,
        )
        val old = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        val handle = checkNotNull(holder.beginReset())

        old.shutdownForReplacement()
        holder.completeReset(handle)
        val replacement = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()

        val restoredStatus = database.vectorizationTaskDao().getById(TASK)?.status
        if (restoredStatus == null) {
            assertThat(replacement.snapshotState().queue).isEmpty()
        } else {
            assertThat(restoredStatus).isIn(
                listOf("interrupted", "extracting_source", "chunking", "vectorizing", "saving", "extracting", "completed"),
            )
            assertThat(replacement.snapshotState().queue.map { it.id }).containsExactly(TASK)
        }
        assertThat(replacement).isNotSameInstanceAs(old)
    }

    @Test
    fun `文件型Room数据库关闭后重开能恢复失败任务并由Queue复用同一task_id重置为pending`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 测试上下文下的唯一数据库文件名，避免与其它用例或历史残留冲突
        val dbName = "nexara-reopen-${java.util.UUID.randomUUID()}.db"
        var firstOpen: NexaraDatabase? = null
        var reopened: NexaraDatabase? = null
        try {
            // 1) 向真实文件型 NexaraDatabase 写入 failed 任务及其 FileEntry
            val initial = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            firstOpen = initial
            seedFileAndTask("failed", initial)
            // 2) 关闭数据库，随后用同一数据库名重新打开
            initial.close()
            reopened = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()

            // 3) failed 状态、task id、workspaceRootUuid/docId 等关键字段确实从 Room 持久层恢复
            val restoredTask = reopened.vectorizationTaskDao().getById(TASK)
            assertThat(restoredTask).isNotNull()
            assertThat(restoredTask!!.status).isEqualTo("failed")
            assertThat(restoredTask.id).isEqualTo(TASK)
            assertThat(restoredTask.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredTask.docId).isEqualTo(DOC)
            assertThat(restoredTask.type).isEqualTo(VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
            assertThat(restoredTask.targetContentHash).isEqualTo("hash-v1")
            assertThat(restoredTask.targetEpoch).isEqualTo(2)

            val restoredDoc = reopened.fileEntryDao().getByUuid(ROOT, DOC)
            assertThat(restoredDoc).isNotNull()
            assertThat(restoredDoc!!.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredDoc.uuid).isEqualTo(DOC)

            // 4) 用重开的 DAO 构造 Queue，复用同一 task id 并安全重置为 pending，不发生主键冲突
            val queue = queue(StandardTestDispatcher(testScheduler), reopened)
            val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)

            assertThat(id).isEqualTo(TASK)
            assertThat(reopened.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
            assertThat(queue.getQueueLength()).isEqualTo(1)
            queue.shutdown()
        } finally {
            // 可靠关闭并删除文件型数据库，防止残留冒充跨 reopen 持久化
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `进程死亡发生于FileEntry提交后Queue接收前时冷启动补建旧值与null的当前reference目标`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "nexara-missing-reference-${java.util.UUID.randomUUID()}.db"
        var firstOpen: NexaraDatabase? = null
        var reopened: NexaraDatabase? = null
        try {
            val initial = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            firstOpen = initial
            seedFilesWithoutTask(initial)
            val oldVectorizedAtFile = initial.fileEntryDao().getByUuid(ROOT, DOC)!!
            initial.fileEntryDao().insert(
                oldVectorizedAtFile.copy(
                    uuid = NULL_DOC,
                    name = "null-${source.name}",
                    hash = "hash-null",
                    materializedPath = "/null-${source.name}",
                    updatedAt = 4,
                    vectorizedAt = null,
                ),
            )
            assertThat(initial.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
            assertThat(initial.vectorizationTaskDao().getByDocId(NULL_DOC)).isEmpty()
            initial.close()

            reopened = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val committedOldFile = reopened.fileEntryDao().getByUuid(ROOT, DOC)
            val committedNullFile = reopened.fileEntryDao().getByUuid(ROOT, NULL_DOC)
            assertThat(committedOldFile).isNotNull()
            assertThat(committedNullFile).isNotNull()
            assertThat(committedOldFile!!.vectorizedAt).isEqualTo(1)
            assertThat(committedOldFile.updatedAt).isEqualTo(2)
            assertThat(committedNullFile!!.vectorizedAt).isNull()
            assertThat(committedNullFile.updatedAt).isEqualTo(4)
            assertThat(reopened.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
            assertThat(reopened.vectorizationTaskDao().getByDocId(NULL_DOC)).isEmpty()

            val coldQueue = queue(
                StandardTestDispatcher(testScheduler),
                reopened,
                ragConfig = RagConfiguration(enableKnowledgeGraph = true),
            )
            assertThat(coldQueue.resumeInterruptedTasks().isSuccess).isTrue()

            val recoveredOld = reopened.vectorizationTaskDao().getByWorkspaceFile(
                ROOT,
                DOC,
                VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )
            val recoveredNull = reopened.vectorizationTaskDao().getByWorkspaceFile(
                ROOT,
                NULL_DOC,
                VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )
            assertThat(recoveredOld).isNotNull()
            assertThat(recoveredNull).isNotNull()
            assertThat(recoveredOld!!.targetContentHash).isEqualTo(committedOldFile.hash)
            assertThat(recoveredOld.targetEpoch).isEqualTo(committedOldFile.updatedAt)
            assertThat(recoveredOld.kgStrategy).isEqualTo("full")
            assertThat(recoveredOld.skipVectorization).isFalse()
            assertThat(recoveredNull!!.targetContentHash).isEqualTo(committedNullFile.hash)
            assertThat(recoveredNull.targetEpoch).isEqualTo(committedNullFile.updatedAt)
            assertThat(recoveredNull.kgStrategy).isEqualTo("full")
            assertThat(recoveredNull.skipVectorization).isFalse()
            coldQueue.shutdown()
        } finally {
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `冷启动补建reference在KG关闭时不携带建图策略`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(
            StandardTestDispatcher(testScheduler),
            ragConfig = RagConfiguration(enableKnowledgeGraph = false),
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        val recovered = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT,
            DOC,
            VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(recovered).isNotNull()
        assertThat(recovered!!.kgStrategy).isNull()
        queue.shutdown()
    }

    @Test
    fun `processing旧目标完成后不能写回或删除newer目标且newer自动接续`() = runTest {
        seedFilesWithoutTask()
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))

        val during = queue.snapshotState().queue
        assertThat(during.map { it.targetEpoch }).containsExactly(2L, 3L).inOrder()
        service.firstResult.complete(DocumentIndexResult.Rebuilt(DOC))
        runCurrent()

        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(active?.error).isNotEqualTo("old failed")
        service.secondStarted.await()
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
    }

    @Test
    fun `processing旧目标失败后不能把newer目标标记failed`() = runTest {
        seedFilesWithoutTask()
        val service = BlockingDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()
        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        service.firstResult.complete(DocumentIndexResult.Failed(IllegalStateException("old failed")))
        runCurrent()

        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(active?.error).isNotEqualTo("old failed")
        service.secondStarted.await()
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
    }

    @Test
    fun `cleanup逐条target CAS删除completed文件引用孤儿`() = runTest {
        seedFilesWithoutTask()
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-target",
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "completed",
                docId = DOC,
                workspaceRootUuid = ROOT,
                sourceMimeType = "text/plain",
                targetContentHash = "hash-v1",
                targetEpoch = 2,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        queue.cleanupCompletedTasks()

        assertThat(database.vectorizationTaskDao().getById("completed-target")).isNull()
    }

    @Test
    fun `newer目标主动取消旧processing service并立即启动newer`() = runTest {
        seedFilesWithoutTask()
        val service = CancellationAwareDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.firstCancelled.await()
        service.secondStarted.await()
        assertThat(database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )?.targetEpoch).isEqualTo(3)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `enqueue发生在旧worker finally边界时newer不会滞留`() = runTest {
        seedFilesWithoutTask()
        val service = FirstCompletesSecondWaitsService()
        val finalEntered = CompletableDeferred<Unit>()
        val allowFinal = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFinalQueueTransitionForTest = {
            if (!finalEntered.isCompleted) {
                finalEntered.complete(Unit)
                allowFinal.await()
            }
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        finalEntered.await()

        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(current.copy(hash = "hash-v2", updatedAt = 3))
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()
        assertThat(service.secondStarted.isCompleted).isFalse()

        allowFinal.complete(Unit)
        runCurrent()

        service.secondStarted.await()
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `newer到达retry delay时取消旧目标且不残留失败态`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("network timeout")
        val retryDelayEntered = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeRetryDelayForTest = {
            retryDelayEntered.complete(Unit)
            awaitCancellation()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        retryDelayEntered.await()

        updateTarget("hash-v2", 3)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.secondStarted.await()
        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(3)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `newer到达failed display delay时取消旧目标且不残留attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            awaitCancellation()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()

        updateTarget("hash-v2", 3)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v2", 3))
        runCurrent()

        service.secondStarted.await()
        val active = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(active?.targetContentHash).isEqualTo("hash-v2")
        assertThat(active?.targetEpoch).isEqualTo(3)
        assertThat(active?.status).isNotEqualTo("failed")
        assertThat(queue.snapshotState().queue.map { it.targetEpoch }).containsExactly(3L)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `clear发生在failed display delay时取消后不回填attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val failureDelayCancelled = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                failureDelayCancelled.complete(Unit)
            }
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()

        queue.clear()
        runCurrent()
        failureDelayCancelled.await()

        assertThat(queue.snapshotState().queue).isEmpty()
        assertThat(queue.snapshotState().currentTask).isNull()
        assertThat(queue.snapshotState().isProcessing).isFalse()
        queue.shutdown()
    }

    @Test
    fun `delete barrier阻止同目标新入队且abort精确恢复failed attention`() = runTest {
        seedFilesWithoutTask()
        val service = FirstFailsSecondWaitsService("fatal rejection")
        val failureDelayEntered = CompletableDeferred<Unit>()
        val releaseFailureDelay = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.beforeFailureDisplayDelayForTest = {
            failureDelayEntered.complete(Unit)
            releaseFailureDelay.await()
        }

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        failureDelayEntered.await()
        releaseFailureDelay.complete(Unit)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertThat(queue.snapshotState().queue.single().status).isEqualTo("failed")

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        assertThat(queue.snapshotState().queue).isEmpty()
        val blocked = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        }.exceptionOrNull()
        assertThat(blocked).isInstanceOf(IllegalStateException::class.java)

        barrier.abort()

        val restored = queue.snapshotState().queue.single()
        assertThat(restored.status).isEqualTo("failed")
        assertThat(restored.targetContentHash).isEqualTo("hash-v1")
        queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        runCurrent()
        service.secondStarted.await()
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `delete barrier abort将active任务CAS为可恢复并重新入队`() = runTest {
        seedFilesWithoutTask()
        val service = CancellationAwareDocumentIndexService()
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.firstStarted.await()

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        service.firstCancelled.await()
        assertThat(queue.snapshotState().queue).isEmpty()

        barrier.abort()
        runCurrent()

        service.secondStarted.await()
        val restored = database.vectorizationTaskDao().getByWorkspaceFile(
            ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        )
        assertThat(restored?.targetEpoch).isEqualTo(2)
        assertThat(restored?.status).isNotEqualTo("failed")
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `queue reset必须等待delete barrier释放后才交接`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(StandardTestDispatcher(testScheduler))
        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()

        val replacementShutdown = async { queue.shutdownForReplacement() }
        runCurrent()
        assertThat(replacementShutdown.isCompleted).isFalse()

        barrier.commit()
        runCurrent()
        replacementShutdown.await()

        assertThat(replacementShutdown.isCompleted).isTrue()
        assertThat(queue.snapshotState().queue).isEmpty()
    }

    @Test
    fun `workspace cancelAndJoin精确删除持久任务且replacement不恢复`() = runTest {
        seedFileAndTask("failed")
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        val queue = queue(StandardTestDispatcher(testScheduler))

        queue.cancelAndJoin(ROOT, listOf(DOC))

        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        queue.shutdown()
        val replacement = queue(StandardTestDispatcher(testScheduler))
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(replacement.snapshotState().queue).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        replacement.shutdown()
    }

    @Test
    fun `workspace cancelAndJoin持久删除前fence拒绝并发enqueue`() = runTest {
        seedFileAndTask("failed")
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val queue = queue(StandardTestDispatcher(testScheduler))
        queue.beforePersistentCancelDeleteForTest = {
            deleteEntered.complete(Unit)
            releaseDelete.await()
        }

        val cancellation = async { queue.cancelAndJoin(ROOT, listOf(DOC)) }
        deleteEntered.await()
        val blocked = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain", "hash-v1", 2)
        }.exceptionOrNull()
        assertThat(blocked).isInstanceOf(IllegalStateException::class.java)

        releaseDelete.complete(Unit)
        cancellation.await()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `delete barrier release不传播state callback异常`() = runTest {
        seedFilesWithoutTask()
        val queue = queue(StandardTestDispatcher(testScheduler))
        var shouldThrow = false
        queue.setOnStateChange { _, _ ->
            if (shouldThrow) error("observer failure")
        }
        shouldThrow = true

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        val failure = runCatching { barrier.commit() }.exceptionOrNull()

        assertThat(failure).isNull()
        shouldThrow = false
        queue.shutdown()
    }

    @Test
    fun `legacy不可迁移时abort先清理vector与KG再安全重跑`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        val embedding = mockk<EmbeddingClient>()
        coEvery { embedding.embedDocuments(any()) } answers {
            EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
        }
        val graph = mockk<GraphExtractor>()
        val graphCalls = AtomicInteger()
        val firstGraphStarted = CompletableDeferred<Unit>()
        val secondGraphStarted = CompletableDeferred<Unit>()
        coEvery { graph.extractAndSave(any(), any()) } coAnswers {
            if (graphCalls.incrementAndGet() == 1) firstGraphStarted.complete(Unit)
            else secondGraphStarted.complete(Unit)
            awaitCancellation()
        }
        coJustRun { graph.clearPersistedGraphForDoc(DOC) }
        val queue = VectorizationQueue(
            vectorStore = VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao()),
            embeddingClient = embedding,
            graphExtractor = graph,
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao(),
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = database.fileEntryDao(),
            documentIndexService = ImmediateDocumentIndexService,
        )
        queue.enqueueDocument(ROOT, DOC, source.name, "content", kgStrategy = "full")
        runCurrent()
        firstGraphStarted.await()
        assertThat(database.vectorDao().getByDocId(DOC)).hasSize(1)

        val barrier = queue.acquireDeleteBarrier(ROOT, listOf(DOC))
        barrier.awaitReady()
        barrier.abort()
        runCurrent()
        secondGraphStarted.await()

        assertThat(database.vectorDao().getByDocId(DOC)).hasSize(1)
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun `Deleted主动取消processing并继续清理artifact与task`() = runTest {
        seedFilesWithoutTask()
        database.vectorDao().insert(
            VectorEntity(
                id = "old-vector",
                docId = DOC,
                content = "old",
                embedding = byteArrayOf(0, 0, 0, 0),
                createdAt = 1,
                fileUuid = DOC,
            ),
        )
        val service = DeleteAwareDocumentIndexService(database)
        val queue = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1", 2))
        runCurrent()
        service.rebuildStarted.await()

        queue.publish(FileIndexEvent.Deleted(ROOT, DOC))
        runCurrent()

        service.rebuildCancelled.await()
        service.deleteCompleted.await()
        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()

        database.fileEntryDao().getByUuid(ROOT, DOC)?.let { database.fileEntryDao().delete(it) }
        val replacement = queue(StandardTestDispatcher(testScheduler), documentIndexService = service)
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(replacement.snapshotState().queue).isEmpty()
        assertThat(database.vectorizationTaskDao().getByDocId(DOC)).isEmpty()
        replacement.shutdown()
    }

    @Test
    fun `resume即使无其他任务也清理completed文件引用孤儿`() = runTest {
        seedFilesWithoutTask()
        updateTarget("hash-v1", 2, vectorizedAt = 2)
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-crash-orphan",
                type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                status = "completed",
                docId = DOC,
                workspaceRootUuid = ROOT,
                sourceMimeType = "text/plain",
                targetContentHash = "hash-v1",
                targetEpoch = 2,
                createdAt = 1,
                updatedAt = 2,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById("completed-crash-orphan")).isNull()
        assertThat(queue.snapshotState().queue).isEmpty()
        queue.shutdown()
    }

    @Test
    fun `resume将可定位legacy任务原子迁移为reference而不盲跑`() = runTest {
        seedFilesWithoutTask()
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "startup-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                kgStrategy = "full",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById("startup-legacy")).isNull()
        assertThat(queue.snapshotState().queue.single().type)
            .isEqualTo(VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        queue.shutdown()
    }

    @Test
    fun `resume对不可定位legacy任务安全清理并转failed attention`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "unsafe-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.vectorDao().insert(
            VectorEntity(
                id = "partial-vector", docId = DOC, content = "partial",
                embedding = byteArrayOf(0, 0, 0, 0), createdAt = 1, fileUuid = DOC,
            ),
        )
        val queue = queue(StandardTestDispatcher(testScheduler))

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorDao().getByDocId(DOC)).isEmpty()
        assertThat(queue.snapshotState().queue.single().status).isEqualTo("failed")
        assertThat(queue.snapshotState().queue.single().id).isEqualTo("unsafe-legacy")
        queue.shutdown()
    }

    @Test
    fun `legacy失败提示经过其他任务排空清理与重启后仍保留`() = runTest {
        seedFilesWithoutTask()
        val entry = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(entry.copy(mimeType = null))
        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "persistent-unsafe-legacy",
                type = "document",
                status = "interrupted",
                docId = DOC,
                docTitle = source.name,
                workspaceRootUuid = ROOT,
                userContent = "legacy content",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val firstQueue = queue(StandardTestDispatcher(testScheduler))
        assertThat(firstQueue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(database.vectorizationTaskDao().getById("persistent-unsafe-legacy")?.status)
            .isEqualTo("failed")

        database.vectorizationTaskDao().insert(
            VectorizationTaskEntity(
                id = "completed-memory",
                type = "memory",
                status = "completed",
                createdAt = 2,
                updatedAt = 2,
            ),
        )
        firstQueue.cleanupCompletedTasks()
        assertThat(database.vectorizationTaskDao().getById("completed-memory")).isNull()
        firstQueue.shutdown()

        val resumedQueue = queue(StandardTestDispatcher(testScheduler))
        assertThat(resumedQueue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(resumedQueue.snapshotState().queue.map { it.id })
            .containsExactly("persistent-unsafe-legacy")
        assertThat(resumedQueue.snapshotState().queue.single().status).isEqualTo("failed")
        resumedQueue.shutdown()
    }

    private suspend fun seedFileAndTask(status: String, db: NexaraDatabase = database) {
        seedFilesWithoutTask(db)
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = TASK, type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, status = status,
            docId = DOC, docTitle = source.name, workspaceRootUuid = ROOT,
            sourceMimeType = "text/plain", targetContentHash = "hash-v1", targetEpoch = 2,
            createdAt = 1, updatedAt = 1,
        ))
    }

    private suspend fun seedFilesWithoutTask(db: NexaraDatabase = database) {
        db.fileEntryDao().insert(FileEntry(
            uuid = ROOT, workspaceRootUuid = ROOT, parentUuid = null, name = "root", hash = "root",
            isDirectory = true, physicalRootPath = source.parentFile!!.absolutePath,
            materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        db.fileEntryDao().insert(FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = source.name,
            hash = "hash-v1", mimeType = "text/plain",
            physicalRootPath = source.parentFile!!.absolutePath, materializedPath = "/${source.name}",
            createdAt = 1, updatedAt = 2, vectorizedAt = 1,
        ))
    }

    private suspend fun updateTarget(hash: String, epoch: Long, vectorizedAt: Long = 1) {
        val current = database.fileEntryDao().getByUuid(ROOT, DOC)!!
        database.fileEntryDao().update(
            current.copy(hash = hash, updatedAt = epoch, vectorizedAt = vectorizedAt),
        )
    }

    private fun queue(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        db: NexaraDatabase = database,
        documentIndexService: DocumentIndexService = ImmediateDocumentIndexService,
        ragConfig: RagConfiguration = RagConfiguration(),
    ) = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = db.vectorDao(),
        vectorizationTaskDao = db.vectorizationTaskDao(),
        ragConfig = ragConfig,
        dispatcher = dispatcher,
        fileEntryDao = db.fileEntryDao(),
        documentIndexService = documentIndexService,
    )

    private class BlockingDocumentIndexService : DocumentIndexService {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<DocumentIndexResult>()
        val secondStarted = CompletableDeferred<Unit>()

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            if (!firstStarted.isCompleted) {
                firstStarted.complete(Unit)
                return firstResult.await()
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private object ImmediateDocumentIndexService : DocumentIndexService {
        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult =
            DocumentIndexResult.Rebuilt(event.fileUuid)

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class CancellationAwareDocumentIndexService : DocumentIndexService {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class FirstCompletesSecondWaitsService : DocumentIndexService {
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) return DocumentIndexResult.Rebuilt(event.fileUuid)
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class FirstFailsSecondWaitsService(
        private val firstFailureMessage: String,
    ) : DocumentIndexService {
        val secondStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            calls += 1
            if (calls == 1) {
                return DocumentIndexResult.Failed(IllegalStateException(firstFailureMessage))
            }
            secondStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
            DocumentIndexResult.Deleted(fileUuid)
    }

    private class DeleteAwareDocumentIndexService(
        private val database: NexaraDatabase,
    ) : DocumentIndexService {
        val rebuildStarted = CompletableDeferred<Unit>()
        val rebuildCancelled = CompletableDeferred<Unit>()
        val deleteCompleted = CompletableDeferred<Unit>()

        override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
            rebuildStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                rebuildCancelled.complete(Unit)
            }
        }

        override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult {
            check(rebuildCancelled.isCompleted) { "delete 必须等待旧 rebuild worker 完成取消" }
            database.vectorDao().deleteByDocId(fileUuid)
            database.vectorizationTaskDao().deleteByWorkspaceFile(workspaceRootUuid, fileUuid)
            deleteCompleted.complete(Unit)
            return DocumentIndexResult.Deleted(fileUuid)
        }
    }

    private companion object {
        const val ROOT = "root"
        const val DOC = "doc"
        const val NULL_DOC = "doc-null"
        const val TASK = "doc-ref-existing"
    }
}
