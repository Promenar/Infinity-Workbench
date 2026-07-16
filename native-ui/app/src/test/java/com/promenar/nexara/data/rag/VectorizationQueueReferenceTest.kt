package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import io.mockk.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VectorizationQueueReferenceTest {
    @Test
    fun `文件索引事件sink将变更持久入队并将删除委托事务清理`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        coEvery { taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) } returns null
        coEvery { taskDao.insertIgnore(any()) } returns 1L
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getByUuid(ROOT, DOC) } returns com.promenar.nexara.data.local.db.entity.FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "note.txt", hash = "hash-v1",
            mimeType = "text/plain", physicalRootPath = "/tmp", materializedPath = "/note.txt",
            createdAt = 1, updatedAt = 1,
        )
        val service = mockk<DocumentIndexService>()
        coEvery { service.delete(ROOT, DOC) } returns DocumentIndexResult.Deleted(DOC)
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
            documentIndexService = service,
        )

        queue.publish(FileIndexEvent.Changed(ROOT, DOC, "hash-v1"))
        queue.publish(FileIndexEvent.Deleted(ROOT, DOC))

        coVerify(exactly = 1) { taskDao.insertIgnore(match { it.docId == DOC }) }
        coVerify(exactly = 1) { service.delete(ROOT, DOC) }
    }

    @Test
    fun `文件引用任务委托事务索引服务且hash竞态不伪装完成`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        coEvery { taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) } returns null
        coEvery { taskDao.insertIgnore(any()) } returns 1L
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getByUuid(ROOT, DOC) } returns com.promenar.nexara.data.local.db.entity.FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = "note.txt", hash = "hash-v1",
            mimeType = "text/plain", physicalRootPath = "/tmp", materializedPath = "/note.txt",
            createdAt = 1, updatedAt = 1,
        )
        val service = mockk<DocumentIndexService>()
        coEvery { service.rebuild(FileIndexEvent.Changed(
            ROOT, DOC, "hash-v1", skipVectorization = true,
            kgStrategy = "semantic", useConfiguredKgStrategy = false,
        )) } returns
            DocumentIndexResult.HashChanged("hash-v2")
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
            documentIndexService = service,
        )

        queue.enqueueDocumentReference(
            ROOT, DOC, "note.txt", "text/plain", kgStrategy = "semantic", skipVectorization = true,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.rebuild(FileIndexEvent.Changed(
                ROOT, DOC, "hash-v1", skipVectorization = true,
                kgStrategy = "semantic", useConfiguredKgStrategy = false,
            ))
        }
        coVerify(exactly = 0) { fileDao.update(any()) }
        assertThat(queue.state.value.queue.single().status).isEqualTo("failed")
    }

    @Test
    fun `失败部分与中断文件任务收到Changed后重置并重新入队`() = runTest {
        listOf("failed", "partial", "interrupted").forEach { status ->
            val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
            coEvery {
                taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
            } returns entity("doc-ref-$status").copy(status = status)
            coEvery { taskDao.update(any()) } returns 1
            val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

            val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain")

            assertThat(id).isEqualTo("doc-ref-$status")
            assertThat(queue.getQueueLength()).isEqualTo(1)
            coVerify(exactly = 1) { taskDao.update(match { it.id == id && it.status == "pending" }) }
        }
    }

    @Test
    fun `文件引用任务先持久化且document不再滥用session外键`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        coEvery { taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) } returns null
        coEvery { taskDao.insertIgnore(any()) } returns 1L
        // StandardTestDispatcher 会在 runTest 收尾执行后台 processor；缺失文件会持久化失败状态并清理 completed。
        coJustRun { taskDao.insert(any()) }
        coJustRun { taskDao.deleteCompletedTasks() }
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain")

        assertThat(id).startsWith("doc-ref-")
        assertThat(queue.getQueueLength()).isEqualTo(1)
        coVerify(exactly = 1) {
            taskDao.insertIgnore(match {
                it.id == id && it.workspaceRootUuid == ROOT && it.docId == DOC &&
                    it.sessionId == null && it.userContent == null &&
                    it.type == VectorizationQueue.TYPE_DOCUMENT_REFERENCE
            })
        }
        advanceUntilIdle()
        coVerify(exactly = 1) { taskDao.deleteCompletedTasks() }
    }

    @Test
    fun `任务持久化失败时不得进入内存队列或伪装成功`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        coEvery { taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) } returns null
        coEvery { taskDao.insertIgnore(any()) } throws IllegalStateException("disk full")
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val failure = runCatching {
            queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(queue.getQueueLength()).isEqualTo(0)
    }

    @Test
    fun `同一workspace文件任务按复合键幂等`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        val existing = entity("doc-ref-existing")
        coEvery { taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE) } returns existing
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain")

        assertThat(id).isEqualTo(existing.id)
        assertThat(queue.getQueueLength()).isEqualTo(0)
        coVerify(exactly = 0) { taskDao.insertIgnore(any()) }
    }

    @Test
    fun `恢复完成后新注册观察者立即收到持久化失败任务`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>()
        val failed = entity("doc-ref-failed").copy(status = "failed", error = "embedding unavailable")
        coJustRun { taskDao.markStaleAsInterrupted(any()) }
        coEvery { taskDao.getRecoverableTasks() } returns emptyList()
        coEvery { taskDao.getAttentionTasks() } returns listOf(failed)
        val fileDao = mockk<FileEntryDao>()
        coEvery { fileDao.getUnvectorizedSupportedFiles(any()) } returns emptyList()
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
        )
        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

        var observed: VectorizationTask? = null
        queue.setOnStateChange { _, current -> observed = current }

        assertThat(observed?.id).isEqualTo(failed.id)
        assertThat(queue.state.value.restored).isTrue()
        assertThat(queue.state.value.currentTask?.status).isEqualTo("failed")
        assertThat(queue.snapshotState()).isEqualTo(queue.state.value)
    }

    @Test
    fun `失败文件任务重新入队时Entity到内存模型保留target`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val existing = entity("target-retry").copy(
            status = "failed",
            targetContentHash = "content-hash",
            targetEpoch = 42,
        )
        coEvery {
            taskDao.getByWorkspaceFile(ROOT, DOC, VectorizationQueue.TYPE_DOCUMENT_REFERENCE)
        } returns existing
        coEvery { taskDao.update(any()) } returns 1
        val queue = queue(taskDao, StandardTestDispatcher(testScheduler))

        queue.enqueueDocumentReference(ROOT, DOC, "note.txt", "text/plain")

        val restored = queue.snapshotState().queue.single()
        assertThat(restored.targetContentHash).isEqualTo("content-hash")
        assertThat(restored.targetEpoch).isEqualTo(42)
    }

    @Test
    fun `启动恢复后的任务再次保存仍保留迁移target`() = runTest {
        val saved = mutableListOf<VectorizationTaskEntity>()
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val recoverable = entity("target-recover").copy(
            status = "interrupted",
            targetContentHash = "migrated-hash",
            targetEpoch = 99,
        )
        coEvery { taskDao.getRecoverableTasks() } returns listOf(recoverable)
        coEvery { taskDao.getAttentionTasks() } returns emptyList()
        coEvery { taskDao.insert(capture(saved)) } just Runs
        val fileDao = mockk<FileEntryDao>(relaxed = true)
        coEvery { fileDao.getUnvectorizedSupportedFiles(any()) } returns emptyList()
        coEvery { fileDao.getByUuid(ROOT, DOC) } returns null
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = mockk(relaxed = true),
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
            fileEntryDao = fileDao,
        )

        assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()
        assertThat(queue.snapshotState().queue.single().targetContentHash).isEqualTo("migrated-hash")
        assertThat(queue.snapshotState().queue.single().targetEpoch).isEqualTo(99)

        advanceUntilIdle()

        assertThat(saved).isNotEmpty()
        assertThat(saved.last().targetContentHash).isEqualTo("migrated-hash")
        assertThat(saved.last().targetEpoch).isEqualTo(99)
    }

    @Test
    fun `处理协程取消不标记failed且不进入内部重试`() = runTest {
        val taskDao = mockk<VectorizationTaskDao>(relaxed = true)
        val saved = mutableListOf<VectorizationTaskEntity>()
        coEvery { taskDao.insert(capture(saved)) } just Runs
        val embeddingClient = mockk<EmbeddingClient>()
        coEvery { embeddingClient.embedDocuments(any()) } throws CancellationException("stop")
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true),
            embeddingClient = embeddingClient,
            graphExtractor = null,
            vectorDao = mockk(relaxed = true),
            vectorizationTaskDao = taskDao,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        queue.enqueueMemory("session", "user", "assistant", "user-id", "assistant-id")
        advanceUntilIdle()

        assertThat(saved.map { it.status }).doesNotContain("failed")
        assertThat(queue.getQueueLength()).isEqualTo(0)
        coVerify(exactly = 1) { embeddingClient.embedDocuments(any()) }
    }

    private fun queue(
        taskDao: VectorizationTaskDao,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = mockk<VectorDao>(relaxed = true),
        vectorizationTaskDao = taskDao,
        dispatcher = dispatcher,
        fileEntryDao = mockk<FileEntryDao>(relaxed = true),
    )

    private fun entity(id: String) = VectorizationTaskEntity(
        id = id,
        type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
        status = "pending",
        docId = DOC,
        docTitle = "note.txt",
        workspaceRootUuid = ROOT,
        sourceMimeType = "text/plain",
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val ROOT = "workspace-root"
        const val DOC = "doc-id"
    }
}
