package com.promenar.nexara.ui.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.VectorTypeCount
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.share.core.ShareImportBatchResult
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val database = mockk<NexaraDatabase>(relaxed = true)
        app = mockk<NexaraApplication>(relaxed = true)

        every { app.database } returns database
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
        every { app.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"))

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
    }

    private fun createViewModel(
        importer: SharedFileImporter? = null,
        requestFactory: ((android.net.Uri, String) -> ShareRequest)? = null,
    ): RagViewModel {
        assertThat(app.vectorizationQueue).isSameInstanceAs(vectorizationQueue)
        val ragPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val ragConfigPersistence = RagConfigPersistence(ragPrefs)
        return RagViewModel(
            app, workspaceRepository, vectorRepository, kgRepository,
            fileOperationRepository, ragConfigPersistence, keywordSearcher,
            injectedImporter = importer,
            injectedRequestFactory = requestFactory,
        )
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
        assertThat(vm.lastQueueError.value).contains("unsafe.bin")
        assertThat(vm.lastQueueError.value).contains("UnsupportedMime")
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

        assertThat(vm.lastQueueError.value).contains("failed.txt")
        assertThat(vm.lastQueueError.value).contains("provider died")
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
    fun `lastQueueError is initially null`() = runTest {
        val vm = createViewModel()

        assertThat(vm.lastQueueError.value).isNull()
    }

    @Test
    fun `lastQueueError captures error when task fails`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
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

        assertThat(vm2.lastQueueError.value).isNotNull()
        assertThat(vm2.lastQueueError.value).contains("API key")
    }

    @Test
    fun `lastQueueError is cleared when new non-failed task starts`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
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
        assertThat(vm2.lastQueueError.value).isNotNull()

        val newTask = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-new",
            type = "document",
            docId = "doc-2",
            docTitle = "New",
            status = "chunking",
            progress = 10.0
        )
        capturedCallback?.invoke(listOf(newTask), newTask)

        assertThat(vm2.lastQueueError.value).isNull()
    }

    @Test
    fun `isIndexing stays true when task fails with error`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
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
    fun `indexingStatus reflects current task chunking state`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
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

        assertThat(vm.indexingStatus.value).contains("切块")
    }

    @Test
    fun `indexingSubStatus mirrors task subStatus`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
        }
        val vm = createViewModel()

        val task = com.promenar.nexara.data.rag.VectorizationTask(
            id = "task-1",
            type = "document",
            docId = "doc-1",
            docTitle = "Test",
            status = "vectorizing",
            progress = 30.0,
            subStatus = "Vectorizing 5/20 chunks"
        )
        capturedCallback?.invoke(listOf(task), task)

        assertThat(vm.indexingSubStatus.value).isEqualTo("Vectorizing 5/20 chunks")
    }
}
