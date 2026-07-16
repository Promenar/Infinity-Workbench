package com.promenar.nexara.ui.rag

import androidx.lifecycle.ViewModelStore
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.VectorTypeCount
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.rag.PendingDocumentIndexCoordinator
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.domain.repository.ReadResult
import com.promenar.nexara.domain.repository.RenameIndexTarget
import com.promenar.nexara.domain.repository.RenameResult
import com.promenar.nexara.share.core.ShareImportBatchResult
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RagViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val workspaceRepository: IWorkspaceRepository = mockk(relaxed = true)
    private val vectorRepository: IVectorRepository = mockk(relaxed = true)
    private val kgRepository: IKnowledgeGraphRepository = mockk(relaxed = true)
    private val fileOperationRepository: IFileOperationRepository = mockk(relaxed = true)
    private val keywordSearcher: KeywordSearcher = mockk(relaxed = true)

    private lateinit var app: NexaraApplication
    private lateinit var sessionDao: SessionDao
    private lateinit var vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue
    private lateinit var filesDir: java.io.File

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val database = mockk<NexaraDatabase>(relaxed = true)
        sessionDao = mockk(relaxed = true)
        app = mockk<NexaraApplication>(relaxed = true)

        every { app.database } returns database
        every { database.sessionDao() } returns sessionDao
        every { app.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        vectorizationQueue = mockk(relaxed = true)
        every { vectorizationQueue.state } returns MutableStateFlow(
            com.promenar.nexara.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = false,
            )
        )
        every { app.vectorizationQueue } returns vectorizationQueue
        every { app.observeVectorizationQueueInstances(any()) } answers {
            firstArg<(com.promenar.nexara.data.rag.VectorizationQueue) -> Unit>().invoke(vectorizationQueue)
            AutoCloseable { }
        }
        filesDir = java.nio.file.Files.createTempDirectory("nexara-rag-vm").toFile()
        every { app.filesDir } returns filesDir

        coEvery { kgRepository.getNodeCount() } returns 0
        coEvery { workspaceRepository.ensureSessionRoot(any()) } returns rootEntry()
        every { workspaceRepository.observeChildren(any(), any()) } returns flowOf(emptyList())
        coEvery { vectorRepository.getCount() } returns 0
        coEvery { vectorRepository.countByType() } returns emptyList()
        coEvery { vectorRepository.countBySession(any()) } returns emptyList()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
        filesDir.deleteRecursively()
    }

    @Test
    fun `新装全局知识库会让仓库选择统一受信父目录而不声明旧路径`() = runTest {
        coEvery { sessionDao.getById(any()) } returns null
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns Unit

        createViewModel()
        advanceUntilIdle()

        assertThat(inserted.captured.id).isEqualTo("__nexara_rag_workspace__")
        assertThat(inserted.captured.workspacePath).isNull()
        coVerify(exactly = 1) {
            workspaceRepository.ensureSessionRoot("__nexara_rag_workspace__")
        }
    }

    @Test
    fun `queue callback在resource reset latch期间只读捕获实例不自锁`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        val firstQueueTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val secondQueueTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val resourceTokenClosed = java.util.concurrent.atomic.AtomicInteger()
        val firstQueue = vectorizationQueue
        val secondQueue = mockk<com.promenar.nexara.data.rag.VectorizationQueue>(relaxed = true)
        every { firstQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { firstQueueTokenClosed.incrementAndGet() }
        }
        every { secondQueue.state } returns MutableStateFlow(
            com.promenar.nexara.data.rag.VectorizationQueue.QueueState(emptyList(), null, false),
        )
        val resource = com.promenar.nexara.utils.SynchronizedResettableResource<com.promenar.nexara.data.rag.VectorizationQueue> {}
        resource.getOrCreate { firstQueue }
        every { app.observeVectorizationQueueInstances(any()) } answers {
            val delegate = resource.observeInstances(firstArg())
            AutoCloseable {
                resourceTokenClosed.incrementAndGet()
                delegate.close()
            }
        }
        val vm = createViewModel()
        val reset = checkNotNull(resource.beginReset())
        val completed = java.util.concurrent.CountDownLatch(1)
        val callbackThread = Thread {
            callback?.invoke(emptyList(), null)
            completed.countDown()
        }
        try {
            callbackThread.start()
            assertThat(completed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        } finally {
            resource.completeReset(reset)
            callbackThread.join(1_000)
        }

        var replacementCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { secondQueue.setOnStateChange(any()) } answers {
            replacementCallback = firstArg()
            AutoCloseable { secondQueueTokenClosed.incrementAndGet() }
        }
        resource.getOrCreate { secondQueue }
        assertThat(firstQueueTokenClosed.get()).isEqualTo(1)
        replacementCallback?.invoke(
            listOf(
                com.promenar.nexara.data.rag.VectorizationTask(
                    id = "replacement-task",
                    type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                    workspaceRootUuid = "rag-root",
                    docId = "replacement-doc",
                    status = "vectorizing",
                    progress = 50.0,
                ),
            ),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "replacement-task",
                type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "replacement-doc",
                status = "vectorizing",
                progress = 50.0,
            ),
        )
        assertThat(vm.indexingDocIds.value).contains("replacement-doc")
        assertThat(vm.indexingProgress.value).isWithin(0.001f).of(0.5f)

        callback?.invoke(
            listOf(
                com.promenar.nexara.data.rag.VectorizationTask(
                    id = "stale-old-task",
                    type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                    workspaceRootUuid = "rag-root",
                    docId = "stale-old-doc",
                    status = "vectorizing",
                    progress = 100.0,
                ),
            ),
            null,
        )
        assertThat(vm.indexingDocIds.value).containsExactly("replacement-doc")
        assertThat(vm.indexingProgress.value).isWithin(0.001f).of(0.5f)

        ViewModelStore().also { it.put("rag", vm) }.clear()
        assertThat(secondQueueTokenClosed.get()).isEqualTo(1)
        assertThat(resourceTokenClosed.get()).isEqualTo(1)
    }

    @Test
    fun `已有旧知识库目录时保守沿用旧路径避免静默丢失数据`() = runTest {
        val legacyRoot = java.io.File(filesDir, "rag_workspace").apply { mkdirs() }
        java.io.File(legacyRoot, "legacy-note.txt").writeText("keep me")
        coEvery { sessionDao.getById(any()) } returns null
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns Unit

        createViewModel()
        advanceUntilIdle()

        assertThat(inserted.captured.workspacePath).isEqualTo(legacyRoot.canonicalPath)
        assertThat(java.io.File(legacyRoot, "legacy-note.txt").readText()).isEqualTo("keep me")
    }

    @Test
    fun `旧版失败残留的空路径声明会清除后改用统一受信父目录`() = runTest {
        val legacyRoot = java.io.File(filesDir, "rag_workspace")
        val stale = SessionEntity(
            id = "__nexara_rag_workspace__",
            agentId = "__system__",
            title = "RAG Workspace",
            workspacePath = legacyRoot.absolutePath,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { sessionDao.getById(stale.id) } returns stale
        val updated = slot<SessionEntity>()
        coEvery { sessionDao.update(capture(updated)) } returns Unit

        createViewModel()
        advanceUntilIdle()

        assertThat(updated.captured.workspacePath).isNull()
        coVerify(exactly = 1) { workspaceRepository.ensureSessionRoot(stale.id) }
    }

    @Test
    fun `全局知识库根初始化失败会转为可见错误而不会逃逸到主线程`() = runTest {
        coEvery { sessionDao.getById(any()) } returns null
        coEvery { workspaceRepository.ensureSessionRoot(any()) } throws
            SecurityException("fixture identity failure")

        val result = runCatching {
            createViewModel().also { advanceUntilIdle() }
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().indexingNotice.value).isNotNull()
        assertThat(result.getOrThrow().indexingNotice.value!!.severity)
            .isEqualTo(NoticeSeverity.Error)
    }

    private fun createViewModel(
        importer: SharedFileImporter? = null,
        requestFactory: ((android.net.Uri, String) -> ShareRequest)? = null,
        pendingIndexCoordinator: PendingDocumentIndexCoordinator? = null,
    ): RagViewModel {
        assertThat(app.vectorizationQueue).isSameInstanceAs(vectorizationQueue)
        val ragPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val ragConfigPersistence = RagConfigPersistence(ragPrefs)
        return RagViewModel(
            app, workspaceRepository, vectorRepository, kgRepository,
            fileOperationRepository, ragConfigPersistence, keywordSearcher,
            injectedImporter = importer,
            injectedRequestFactory = requestFactory,
            ragWorkspaceIoContext = testDispatcher,
            injectedPendingIndexCoordinator = pendingIndexCoordinator,
        )
    }

    @Test
    fun `Home重命名失败会让新建Folder ViewModel看到共享pending并完成重试`() = runTest {
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        val homeVm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        homeVm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(homeVm.pendingRenameIndexTargets.value).containsExactly(target)

        val folderVm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        assertThat(folderVm.pendingRenameIndexTargets.value).containsExactly(target)

        shouldFail = false
        folderVm.retryPendingRenameIndexNow()
        advanceUntilIdle()
        assertThat(folderVm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(homeVm.pendingRenameIndexTargets.value).isEmpty()
    }

    @Test
    fun `queue失败notice不会被共享pending增减覆盖或清除`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val event = com.promenar.nexara.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        val failedTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "failed-ref",
            type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            workspaceRootUuid = "rag-root",
            docId = "queue-doc",
            status = "failed",
            error = "embedding unavailable",
        )
        callback?.invoke(listOf(failedTask), failedTask)

        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).hasSize(1)
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)

        shouldFail = false
        assertThat(coordinator.retry(event)).isTrue()
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `queue处理中共享pending清空仍保持indexing`() = runTest {
        val processingState = MutableStateFlow(
            com.promenar.nexara.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = true,
            ),
        )
        every { vectorizationQueue.state } returns processingState
        var shouldFail = true
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { if (shouldFail) error("queue unavailable") },
        )
        val event = com.promenar.nexara.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.isIndexing.value).isTrue()

        shouldFail = false
        assertThat(coordinator.retry(event)).isTrue()
        advanceUntilIdle()

        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `共享pending存在时idle queue回调不会隐藏重试入口`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        val queueState = MutableStateFlow(
            com.promenar.nexara.data.rag.VectorizationQueue.QueueState(
                queue = emptyList(),
                currentTask = null,
                isProcessing = true,
            ),
        )
        every { vectorizationQueue.state } returns queueState
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { error("queue unavailable") },
        )
        val event = com.promenar.nexara.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = "doc-a",
            contentHash = "hash-a",
            targetEpoch = 101L,
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        runCatching { coordinator.publish(event) }
        advanceUntilIdle()
        assertThat(vm.isIndexing.value).isTrue()

        val active = com.promenar.nexara.data.rag.VectorizationTask(
            id = "active-ref",
            type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            workspaceRootUuid = "rag-root",
            docId = "queue-doc",
            status = "vectorizing",
        )
        callback?.invoke(listOf(active), active)
        assertThat(vm.isIndexing.value).isTrue()

        queueState.value = queueState.value.copy(isProcessing = false)
        callback?.invoke(emptyList(), null)

        assertThat(vm.pendingRenameIndexTargets.value).hasSize(1)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `共享pending存在时dismiss会立即保持可见重试入口`() = runTest {
        val coordinator = PendingDocumentIndexCoordinator(
            FileIndexEventSink { error("queue unavailable") },
        )
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        val vm = createViewModel(pendingIndexCoordinator = coordinator)
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        vm.dismissQueueError()
        advanceUntilIdle()

        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
        assertThat(vm.indexingNotice.value).isNull()
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `知识库导入复用共享安全管线并保留目标文件夹`() = runTest {
        val uri = mockk<android.net.Uri>()
        every { uri.lastPathSegment } returns "unsafe.bin"
        every { uri.toString() } returns "content://fixture/unsafe.bin"
        val request = ShareRequest(
            uris = listOf(uri),
            mimeType = "application/octet-stream",
            fingerprint = "fixture",
            canonicalSizeBytes = 7,
            requestId = "request",
            targetWorkspaceRootUuid = "rag-root",
        )
        val importer = mockk<SharedFileImporter>()
        coEvery {
            importer.import(request, "rag-root", null, "folder-docs")
        } returns ShareImportBatchResult(
            listOf(
                ShareImportItem(
                    uri = uri,
                    displayName = "unsafe.bin",
                    mimeType = "application/octet-stream",
                    sizeBytes = 4,
                    status = ShareImportStatus.Rejected,
                    reason = ShareRejectReason.UnsupportedMime,
                )
            )
        )
        val vm = createViewModel(
            importer = importer,
            requestFactory = { requestedUri, root ->
                assertThat(requestedUri).isEqualTo(uri)
                assertThat(root).isEqualTo("rag-root")
                request
            },
        )

        vm.importDocuments(listOf(uri), folderId = "folder-docs")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            importer.import(request, "rag-root", null, "folder-docs")
        }
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_IMPORT_FAILED)
        assertThat(vm.indexingNotice.value!!.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(vm.indexingNotice.value!!.technical).contains("unsafe.bin")
        assertThat(vm.indexingNotice.value!!.technical).contains("UnsupportedMime")
    }

    @Test
    fun `共享导入器异常会成为可见失败且不会被部分成功清除`() = runTest {
        val good = mockk<android.net.Uri>()
        val failed = mockk<android.net.Uri>()
        every { good.lastPathSegment } returns "good.txt"
        every { good.toString() } returns "content://fixture/good.txt"
        every { failed.lastPathSegment } returns "failed.txt"
        every { failed.toString() } returns "content://fixture/failed.txt"
        val importer = mockk<SharedFileImporter>()
        val goodRequest = requestFor(good)
        val failedRequest = requestFor(failed)
        coEvery { importer.import(goodRequest, "rag-root", null, "rag-root") } returns
            ShareImportBatchResult(
                listOf(
                    ShareImportItem(
                        uri = good,
                        displayName = "good.txt",
                        mimeType = "text/plain",
                        sizeBytes = 4,
                        status = ShareImportStatus.Created,
                    )
                )
            )
        coEvery { importer.import(failedRequest, "rag-root", null, "rag-root") } throws
            java.io.IOException("provider died")
        val vm = createViewModel(
            importer = importer,
            requestFactory = { uri, _ ->
                if (uri.lastPathSegment == "good.txt") goodRequest else failedRequest
            },
        )

        vm.importDocuments(listOf(good, failed))
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_IMPORT_FAILED)
        assertThat(vm.indexingNotice.value!!.technical).contains("failed.txt")
        assertThat(vm.indexingNotice.value!!.technical).contains("provider died")
    }

    @Test
    fun `批量重新索引会委托到仓库读取并入队`() = runTest {
        val root = "rag-root"
        val doc = FileEntry(
            uuid = "doc-1",
            parentUuid = root,
            name = "doc.txt",
            hash = "current-hash",
            mimeType = "text/plain",
            physicalRootPath = "/tmp/doc.txt",
            materializedPath = "/doc.txt",
            isDirectory = false,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { workspaceRepository.getByUuid(root, "doc-1") } returns doc
        val vm = createViewModel()
        advanceUntilIdle()
        vm.reindexDocuments(listOf("doc-1"))
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.getByUuid(root, "doc-1") }
        coVerify(exactly = 0) { fileOperationRepository.readFileRange(root, "doc-1") }
        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference(
                workspaceRootUuid = root,
                docId = "doc-1",
                docTitle = "doc.txt",
                sourceMimeType = "text/plain",
                targetContentHash = "current-hash",
                targetEpoch = 1L,
                kgStrategy = any(),
            )
        }
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `批量重新索引空输入给用户级反馈并不执行操作`() = runTest {
        val vm = createViewModel()
        vm.reindexDocuments(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 0) { fileOperationRepository.readFileRange(any(), any()) }
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_WARNING)
    }

    @Test
    fun `移动多个文档成功时会触发回调并清空失败列表`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } returns Unit
        coEvery { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") } returns Unit

        var result = false
        var failed = listOf<String>()
        vm.moveDocuments(listOf("doc-1", "doc-2"), "folder-a") { ok, failedIds ->
            result = ok
            failed = failedIds
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") }
        assertThat(result).isTrue()
        assertThat(failed).isEmpty()
    }

    @Test
    fun `移动失败时回调 false 并记录失败列表`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } returns Unit
        coEvery {
            workspaceRepository.updateParent("rag-root", "doc-2", "folder-a")
        } throws RuntimeException("move failed")

        var result = true
        var failed = listOf<String>()
        vm.moveDocuments(listOf("doc-1", "doc-2"), "folder-a") { ok, failedIds ->
            result = ok
            failed = failedIds
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-2", "folder-a") }
        assertThat(result).isFalse()
        assertThat(failed).containsExactly("doc-2")
        assertThat(vm.indexingNotice.value).isNotNull()
        assertThat(vm.indexingNotice.value!!.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.indexingNotice.value!!.technical).doesNotContain("move failed")
    }

    @Test
    fun `移动错误会替换旧索引失败且不误暴露索引重试`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-1",
                status = "failed",
            ),
        )
        assertThat(vm.canRetryLastFailedIndex.value).isTrue()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } throws
            IllegalStateException("private move detail")

        vm.moveDocuments(listOf("doc-1"), "folder-a")
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
    }

    @Test
    fun `移动失败提示不会被空队列状态回调意外清除`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } throws
            IllegalStateException("private move detail")

        vm.moveDocuments(listOf("doc-1"), "folder-a")
        advanceUntilIdle()
        callback?.invoke(emptyList(), null)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_MOVE_FAILED)
        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `移动进行中重复提交被门禁且不会二次写入`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") } coAnswers {
            gate.await()
        }
        val vm = createViewModel()
        advanceUntilIdle()

        var secondFailedIds = emptyList<String>()
        vm.moveDocuments(listOf("doc-1"), "folder-a")
        testDispatcher.scheduler.runCurrent()
        assertThat(vm.isMovingDocuments.value).isTrue()
        vm.moveDocuments(listOf("doc-1"), "folder-a") { _, failedIds ->
            secondFailedIds = failedIds
        }
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { workspaceRepository.updateParent("rag-root", "doc-1", "folder-a") }
        assertThat(secondFailedIds).containsExactly("doc-1")

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.isMovingDocuments.value).isFalse()
    }

    @Test
    fun `批量删除部分失败会继续处理并逐项返回失败标识`() = runTest {
        coEvery { workspaceRepository.permanentDelete("rag-root", "doc-1") } returns Unit
        coEvery { workspaceRepository.permanentDelete("rag-root", "doc-2") } throws
            IllegalStateException("private delete detail")
        coEvery { workspaceRepository.permanentDelete("rag-root", "doc-3") } returns Unit
        val vm = createViewModel()
        advanceUntilIdle()

        var failedIds = emptyList<String>()
        vm.deleteDocuments(listOf("doc-1", "doc-2", "doc-3")) { _, failed ->
            failedIds = failed
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.permanentDelete("rag-root", "doc-1") }
        coVerify(exactly = 1) { workspaceRepository.permanentDelete("rag-root", "doc-2") }
        coVerify(exactly = 1) { workspaceRepository.permanentDelete("rag-root", "doc-3") }
        assertThat(failedIds).containsExactly("doc-2")
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_DELETE_FAILED)
        assertThat(vm.indexingNotice.value?.technical).doesNotContain("private delete detail")
    }

    @Test
    fun `批量删除收到取消信号时立即传播且不继续处理后续项`() = runTest {
        coEvery { workspaceRepository.permanentDelete("rag-root", "doc-1") } throws
            kotlinx.coroutines.CancellationException("cancel delete")
        val vm = createViewModel()
        advanceUntilIdle()
        var callbackInvoked = false

        vm.deleteDocuments(listOf("doc-1", "doc-2")) { _, _ -> callbackInvoked = true }
        advanceUntilIdle()

        coVerify(exactly = 1) { workspaceRepository.permanentDelete("rag-root", "doc-1") }
        coVerify(exactly = 0) { workspaceRepository.permanentDelete("rag-root", "doc-2") }
        assertThat(callbackInvoked).isFalse()
        assertThat(vm.isDeletingDocuments.value).isFalse()
    }

    private fun requestFor(uri: android.net.Uri) = ShareRequest(
        uris = listOf(uri),
        mimeType = "text/plain",
        fingerprint = uri.toString(),
        canonicalSizeBytes = uri.toString().length,
        requestId = uri.lastPathSegment.orEmpty(),
        targetWorkspaceRootUuid = "rag-root",
    )

    @Test
    fun `init loadStats uses document count from workspace`() = runTest {
        coEvery { kgRepository.getNodeCount() } returns 5

        val vm = createViewModel()

        coVerify { kgRepository.getNodeCount() }
        assertThat(vm.stats.value.graphEntityCount).isEqualTo(5)
    }

    @Test
    fun `loadCollections refreshes stats from repositories`() = runTest {
        coEvery { kgRepository.getNodeCount() } returns 0

        val vm = createViewModel()

        coEvery { kgRepository.getNodeCount() } returns 7

        vm.loadCollections()

        assertThat(vm.stats.value.graphEntityCount).isEqualTo(7)
    }

    @Test
    fun `search clears results on blank query`() = runTest {
        val vm = createViewModel()
        vm.search("  ")

        assertThat(vm.searchResults.value).isEmpty()
    }

    @Test
    fun `folders comes from root scoped children`() = runTest {
        val dirs = listOf(
            FileEntry(
                uuid = "f1", parentUuid = null, name = "Folder 1", hash = "",
                physicalRootPath = "/", materializedPath = "/Folder 1",
                isDirectory = true, createdAt = 100L, updatedAt = 100L
            ),
            FileEntry(
                uuid = "f2", parentUuid = null, name = "Folder 2", hash = "",
                physicalRootPath = "/", materializedPath = "/Folder 2",
                isDirectory = true, createdAt = 200L, updatedAt = 200L
            )
        )
        every { workspaceRepository.observeChildren("rag-root", "rag-root") } returns flowOf(dirs)

        val vm = createViewModel()

        assertThat(vm.folders.value).hasSize(2)
        assertThat(vm.folders.value[0].id).isEqualTo("f1")
    }

    @Test
    fun `documents comes from root scoped children non-directory entries`() = runTest {
        val entries = listOf(
            FileEntry(
                uuid = "d1", parentUuid = null, name = "Doc 1.txt", hash = "abc",
                physicalRootPath = "/", materializedPath = "/Doc 1.txt",
                isDirectory = false, createdAt = 100L, updatedAt = 100L
            )
        )
        every { workspaceRepository.observeChildren("rag-root", "rag-root") } returns flowOf(entries)

        val vm = createViewModel()

        assertThat(vm.documents.value).hasSize(1)
        assertThat(vm.documents.value[0].id).isEqualTo("d1")
    }

    @Test
    fun `目录重命名会为全部受影响文件发布当前target事件`() = runTest {
        val targets = listOf(
            RenameIndexTarget("doc-a", "hash-a", 101L),
            RenameIndexTarget("doc-b", "hash-b", 102L),
        )
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = targets,
        )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        targets.forEach { target ->
            coVerify(exactly = 1) {
                vectorizationQueue.publish(
                    com.promenar.nexara.data.rag.FileIndexEvent.Changed(
                        workspaceRootUuid = "rag-root",
                        fileUuid = target.fileUuid,
                        contentHash = target.targetHash,
                        targetEpoch = target.targetEpoch,
                    ),
                )
            }
        }
    }

    @Test
    fun `目录重命名提交后索引发布失败只显示索引警告`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        coEvery { vectorizationQueue.publish(any()) } throws
            IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Warning)
        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_WARNING)
        coVerify(exactly = 1) {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        }
    }

    @Test
    fun `目录重命名发布失败会跨no-op重命名保留待补偿target`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returnsMany listOf(
            RenameResult.Success(
                name = "renamed",
                targetHash = "folder-hash",
                targetEpoch = 100L,
                changed = true,
                affectedTargets = listOf(target),
            ),
            RenameResult.Success(
                name = "renamed",
                targetHash = "folder-hash",
                targetEpoch = 100L,
                changed = false,
                affectedTargets = emptyList(),
            ),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)

        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
    }

    @Test
    fun `目录重命名补偿重试会保留部分失败并在全部成功后清除`() = runTest {
        val first = RenameIndexTarget("doc-a", "hash-a", 101L)
        val second = RenameIndexTarget("doc-b", "hash-b", 102L)
        val firstEvent = com.promenar.nexara.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = first.fileUuid,
            contentHash = first.targetHash,
            targetEpoch = first.targetEpoch,
        )
        val secondEvent = com.promenar.nexara.data.rag.FileIndexEvent.Changed(
            workspaceRootUuid = "rag-root",
            fileUuid = second.fileUuid,
            contentHash = second.targetHash,
            targetEpoch = second.targetEpoch,
        )
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(first, second),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(first, second).inOrder()

        coEvery { vectorizationQueue.publish(firstEvent) } returns Unit
        coEvery { vectorizationQueue.publish(secondEvent) } throws IllegalStateException("still unavailable")
        vm.retryPendingRenameIndexNow()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(second)

        coEvery { vectorizationQueue.publish(secondEvent) } returns Unit
        vm.retryPendingRenameIndexNow()
        assertThat(vm.pendingRenameIndexTargets.value).isEmpty()
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `目录重命名补偿重试遇到取消会传播且保留未发布target`() = runTest {
        val target = RenameIndexTarget("doc-a", "hash-a", 101L)
        coEvery {
            workspaceRepository.rename("rag-root", "folder", "renamed", null)
        } returns RenameResult.Success(
            name = "renamed",
            targetHash = "folder-hash",
            targetEpoch = 100L,
            changed = true,
            affectedTargets = listOf(target),
        )
        coEvery { vectorizationQueue.publish(any()) } throws IllegalStateException("queue unavailable")
        val vm = createViewModel()
        advanceUntilIdle()
        vm.renameFolder("folder", "renamed")
        advanceUntilIdle()

        coEvery { vectorizationQueue.publish(any()) } throws CancellationException("cancel retry")
        var cancellationPropagated = false
        try {
            vm.retryPendingRenameIndexNow()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertThat(cancellationPropagated).isTrue()
        assertThat(vm.pendingRenameIndexTargets.value).containsExactly(target)
    }

    private fun rootEntry() = FileEntry(
        uuid = "rag-root",
        workspaceRootUuid = "rag-root",
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/rag-root",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun `indexingNotice is initially null`() = runTest {
        val vm = createViewModel()

        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `indexingNotice captures task failed state`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm2 = createViewModel()

        val failedTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "API key invalid"
        )

        capturedCallback?.invoke(listOf(failedTask), failedTask)

        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm2.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(vm2.indexingNotice.value?.technical).contains("API key")
    }

    @Test
    fun `indexingNotice updates when non-failed task starts`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm2 = createViewModel()

        val failedTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "API key invalid"
        )
        capturedCallback?.invoke(listOf(failedTask), failedTask)
        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)

        val newTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-new",
            type = "document",
            docId = "doc-2",
            docTitle = "New",
            status = "chunking",
            progress = 10.0
        )
        capturedCallback?.invoke(listOf(newTask), newTask)

        assertThat(vm2.indexingNotice.value).isNotNull()
        assertThat(vm2.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_CHUNKING)
    }

    @Test
    fun `isIndexing stays true when task fails with error`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }

        val vm = createViewModel()

        val failedTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-err",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "failed",
            progress = 0.0,
            error = "Network error"
        )
        capturedCallback?.invoke(listOf(failedTask), failedTask)

        assertThat(vm.isIndexing.value).isTrue()
    }

    @Test
    fun `indexingNotice reflects current task chunking state`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        val chunkingTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-1",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "chunking",
            progress = 10.0
        )
        capturedCallback?.invoke(listOf(chunkingTask), chunkingTask)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_CHUNKING)
        assertThat(vm.indexingNotice.value?.severity).isEqualTo(NoticeSeverity.Info)
    }

    @Test
    fun `indexingNotice retains format args for vectorizing task`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        val task = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-1",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "vectorizing",
            progress = 30.0,
            totalChunks = 20,
            subStatus = "Vectorizing 5/20 chunks"
        )
        capturedCallback?.invoke(listOf(task), task)

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_VECTORIZING)
        assertThat(vm.indexingNotice.value?.formatArgs).containsExactly("20")
    }

    @Test
    fun `普通文档失败会建立真实重试目标并重新读取后入队`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val entry = FileEntry(
            uuid = "doc-1",
            workspaceRootUuid = "rag-root",
            parentUuid = "rag-root",
            name = "doc.txt",
            hash = "hash",
            mimeType = "text/plain",
            physicalRootPath = "/tmp/doc.txt",
            materializedPath = "/doc.txt",
            isDirectory = false,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { workspaceRepository.getByUuid("rag-root", "doc-1") } returns entry
        coEvery { fileOperationRepository.readFileRange("rag-root", "doc-1") } returns ReadResult(
            uuid = "doc-1",
            name = "doc.txt",
            totalLines = 1,
            startLine = 0,
            endLine = 0,
            content = "fresh content",
            hash = "hash",
            lastModified = 1L,
        )
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "failed-document",
                type = "document",
                workspaceRootUuid = "rag-root",
                docId = "doc-1",
                docTitle = "doc.txt",
                status = "failed",
                error = "provider secret detail",
            ),
        )

        assertThat(vm.canRetryLastFailedIndex.value).isTrue()
        vm.retryLastFailedIndex()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            vectorizationQueue.enqueueDocumentReference(
                workspaceRootUuid = "rag-root",
                docId = "doc-1",
                docTitle = "doc.txt",
                sourceMimeType = "text/plain",
                targetContentHash = "hash",
                targetEpoch = 1L,
                kgStrategy = any(),
            )
        }
        coVerify(exactly = 1) { vectorizationQueue.cancelAndJoin("rag-root", listOf("doc-1")) }
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        assertThat(vm.isRetryingLastFailedIndex.value).isFalse()
        assertThat(vm.indexingNotice.value).isNull()
    }

    @Test
    fun `文件引用失败会调用持久化引用重试且重试中拒绝重复触发`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val retryGate = CompletableDeferred<Unit>()
        coEvery { vectorizationQueue.retryDocumentReference("rag-root", "doc-ref") } coAnswers {
            retryGate.await()
            true
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-ref",
                docTitle = "ref.pdf",
                status = "partial",
            ),
        )

        vm.retryLastFailedIndex()
        vm.retryLastFailedIndex()

        assertThat(vm.isRetryingLastFailedIndex.value).isTrue()
        coVerify(exactly = 1) { vectorizationQueue.retryDocumentReference("rag-root", "doc-ref") }

        retryGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.isRetryingLastFailedIndex.value).isFalse()
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
    }

    @Test
    fun `缺少稳定文档身份的失败状态不会暴露重试入口`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()

        callback?.invoke(
            emptyList(),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "failed-without-target",
                type = "document",
                status = "failed",
            ),
        )

        assertThat(vm.indexingNotice.value?.code).isEqualTo(IndexingNotice.CODE_FAILED)
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        vm.retryLastFailedIndex()
        advanceUntilIdle()
        coVerify(exactly = 0) { vectorizationQueue.enqueueDocument(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { vectorizationQueue.retryDocumentReference(any(), any()) }
    }

    @Test
    fun `关闭失败提示会同步清除重试目标`() = runTest {
        var callback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            callback = firstArg()
            AutoCloseable { }
        }
        val vm = createViewModel()
        callback?.invoke(
            emptyList(),
            com.promenar.nexara.data.rag.VectorizationTask(
                id = "failed-reference",
                type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
                workspaceRootUuid = "rag-root",
                docId = "doc-ref",
                status = "failed",
            ),
        )
        assertThat(vm.canRetryLastFailedIndex.value).isTrue()

        vm.dismissQueueError()
        advanceUntilIdle()

        assertThat(vm.indexingNotice.value).isNull()
        assertThat(vm.isIndexing.value).isFalse()
        assertThat(vm.canRetryLastFailedIndex.value).isFalse()
        coVerify(exactly = 1) { vectorizationQueue.cancelAndJoin("rag-root", listOf("doc-ref")) }
    }
}
