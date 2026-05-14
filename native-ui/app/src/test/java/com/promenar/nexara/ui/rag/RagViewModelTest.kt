package com.promenar.nexara.ui.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IFolderRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.VectorTypeCount
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.usecase.DeleteDocumentUseCase
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RagViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val documentRepository: IDocumentRepository = mockk(relaxed = true)
    private val vectorRepository: IVectorRepository = mockk(relaxed = true)
    private val kgRepository: IKnowledgeGraphRepository = mockk(relaxed = true)
    private val folderRepository: IFolderRepository = mockk(relaxed = true)
    private val keywordSearcher: KeywordSearcher = mockk(relaxed = true)

    private lateinit var app: NexaraApplication

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val database = mockk<NexaraDatabase>(relaxed = true)
        app = mockk<NexaraApplication>(relaxed = true)

        every { app.database } returns database
        every { app.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        every { app.vectorizationQueue } returns mockk(relaxed = true)
        every { app.documentImporter } returns mockk(relaxed = true)

        coEvery { documentRepository.getCount() } returns 0
        coEvery { kgRepository.getNodeCount() } returns 0
        every { documentRepository.observeAll() } returns flowOf(emptyList())
        every { folderRepository.observeAll() } returns flowOf(emptyList())
        coEvery { vectorRepository.getCount() } returns 0
        coEvery { vectorRepository.countByType() } returns emptyList()
        coEvery { vectorRepository.countBySession(any()) } returns emptyList()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): RagViewModel {
        val ragPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val ragConfigPersistence = RagConfigPersistence(ragPrefs)
        return RagViewModel(
            app, documentRepository, vectorRepository, kgRepository, folderRepository,
            DeleteDocumentUseCase(documentRepository, vectorRepository),
            ragConfigPersistence,
            keywordSearcher
        )
    }

    @Test
    fun `init loadStats delegates to documentRepository getCount`() = runTest {
        coEvery { documentRepository.getCount() } returns 10

        val vm = createViewModel()

        coVerify { documentRepository.getCount() }
        assertThat(vm.stats.value.documentCount).isEqualTo(10)
    }

    @Test
    fun `init loadStats delegates to kgRepository getNodeCount`() = runTest {
        coEvery { kgRepository.getNodeCount() } returns 5

        val vm = createViewModel()

        coVerify { kgRepository.getNodeCount() }
        assertThat(vm.stats.value.graphEntityCount).isEqualTo(5)
    }

    @Test
    fun `loadCollections refreshes stats from repositories`() = runTest {
        coEvery { documentRepository.getCount() } returns 0
        coEvery { kgRepository.getNodeCount() } returns 0

        val vm = createViewModel()

        coEvery { documentRepository.getCount() } returns 42
        coEvery { kgRepository.getNodeCount() } returns 7

        vm.loadCollections()

        assertThat(vm.stats.value.documentCount).isEqualTo(42)
        assertThat(vm.stats.value.graphEntityCount).isEqualTo(7)
    }

    @Test
    fun `deleteDocuments calls documentRepository delete and vectorRepository deleteByDocument`() = runTest {
        val ids = listOf("doc1", "doc2", "doc3")

        val vm = createViewModel()
        vm.deleteDocuments(ids)

        coVerify { documentRepository.delete("doc1") }
        coVerify { documentRepository.delete("doc2") }
        coVerify { documentRepository.delete("doc3") }
        coVerify { vectorRepository.deleteByDocument("doc1") }
        coVerify { vectorRepository.deleteByDocument("doc2") }
        coVerify { vectorRepository.deleteByDocument("doc3") }
    }

    @Test
    fun `deleteDocuments refreshes stats after deletion`() = runTest {
        coEvery { documentRepository.getCount() } returns 0

        val vm = createViewModel()

        coEvery { documentRepository.getCount() } returns 8
        coEvery { kgRepository.getNodeCount() } returns 3

        vm.deleteDocuments(listOf("doc1"))

        assertThat(vm.stats.value.documentCount).isEqualTo(8)
    }

    @Test
    fun `loadDocumentsForFolder delegates to documentRepository getByFolderId`() = runTest {
        val docs = listOf(
            com.promenar.nexara.domain.model.Document(
                id = "d1",
                folderId = "f1",
                title = "Doc 1",
                content = "content"
            )
        )
        coEvery { documentRepository.getByFolderId("f1") } returns docs

        val vm = createViewModel()
        vm.loadDocumentsForFolder("f1")

        assertThat(vm.documents.value).hasSize(1)
        assertThat(vm.documents.value[0].id).isEqualTo("d1")
    }

    @Test
    fun `search filters documents by title case insensitive`() = runTest {
        val docs = listOf(
            com.promenar.nexara.domain.model.Document(
                id = "d1", folderId = "f1", title = "Kotlin Guide", content = ""
            ),
            com.promenar.nexara.domain.model.Document(
                id = "d2", folderId = "f1", title = "Java Tutorial", content = ""
            ),
            com.promenar.nexara.domain.model.Document(
                id = "d3", folderId = "f1", title = "kotlin advanced", content = ""
            )
        )
        every { documentRepository.observeAll() } returns flowOf(docs)

        val vm = createViewModel()
        vm.search("kotlin")

        assertThat(vm.searchResults.value).hasSize(2)
        assertThat(vm.searchResults.value.map { it.document.id }).containsExactly("d1", "d3")
    }

    @Test
    fun `search clears results on blank query`() = runTest {
        val vm = createViewModel()
        vm.search("  ")

        assertThat(vm.searchResults.value).isEmpty()
    }

    @Test
    fun `createFolder delegates to folderRepository`() = runTest {
        val vm = createViewModel()
        vm.createFolder("New Folder")

        coVerify { folderRepository.create(match { it.name == "New Folder" }) }
    }

    @Test
    fun `deleteCollection delegates to folderRepository`() = runTest {
        val folder = com.promenar.nexara.domain.model.Folder(
            id = "f1", name = "ToDelete", createdAt = 1000L
        )
        coEvery { folderRepository.getById("f1") } returns folder

        val vm = createViewModel()
        vm.deleteCollection("f1")

        coVerify { folderRepository.delete(folder) }
    }

    @Test
    fun `deleteCollection does nothing when folder not found`() = runTest {
        coEvery { folderRepository.getById("missing") } returns null

        val vm = createViewModel()
        vm.deleteCollection("missing")

        coVerify(exactly = 0) { folderRepository.delete(any()) }
    }

    @Test
    fun `init loadStats uses vectorRepository for memory count`() = runTest {
        coEvery { vectorRepository.countByType() } returns listOf(
            VectorTypeCount(type = "memory", count = 15),
            VectorTypeCount(type = "doc", count = 8)
        )

        val vm = createViewModel()

        assertThat(vm.stats.value.memoryCount).isEqualTo(15)
    }

    @Test
    fun `folders comes from folderRepository observeAll`() = runTest {
        val folders = listOf(
            com.promenar.nexara.domain.model.Folder(id = "f1", name = "Folder 1", createdAt = 100L),
            com.promenar.nexara.domain.model.Folder(id = "f2", name = "Folder 2", createdAt = 200L)
        )
        every { folderRepository.observeAll() } returns flowOf(folders)

        val vm = createViewModel()

        assertThat(vm.folders.value).hasSize(2)
        assertThat(vm.folders.value[0].id).isEqualTo("f1")
    }

    // ── 错误状态持久化（Bug 2 修复验证） ──────────────────────────

    @Test
    fun `lastQueueError is initially null`() = runTest {
        val vm = createViewModel()

        assertThat(vm.lastQueueError.value).isNull()
    }

    @Test
    fun `lastQueueError captures error when task fails`() = runTest {
        // 拦截 vectorizationQueue 的 setOnStateChange 回调
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { app.vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
        }

        // 重新创建 ViewModel 以触发 observeQueue
        val vm2 = createViewModel()

        // 模拟向量化失败
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
        every { app.vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
        }

        val vm2 = createViewModel()

        // 先模拟失败
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

        // 然后模拟新任务开始
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
        every { app.vectorizationQueue.setOnStateChange(any()) } answers {
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

    // ── 队列状态观测 ──────────────────────────────────────────────

    @Test
    fun `indexingStatus reflects current task chunking state`() = runTest {
        var capturedCallback: ((List<com.promenar.nexara.data.rag.VectorizationTask>, com.promenar.nexara.data.rag.VectorizationTask?) -> Unit)? = null
        every { app.vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
        }
        every { app.vectorizationQueue.getState() } returns mockk(relaxed = true)

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
        every { app.vectorizationQueue.setOnStateChange(any()) } answers {
            capturedCallback = firstArg()
        }
        every { app.vectorizationQueue.getState() } returns mockk(relaxed = true)

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

    @Test
    fun `deleteCollection cascade deletes documents and vectors`() = runTest {
        val folder = Folder(id = "f1", name = "Test")
        val docs = listOf(
            Document(id = "d1", folderId = "f1", title = "Doc1", content = "a"),
            Document(id = "d2", folderId = "f1", title = "Doc2", content = "b")
        )
        coEvery { documentRepository.getByFolderId("f1") } returns docs
        coEvery { folderRepository.getById("f1") } returns folder

        val vm = createViewModel()
        vm.deleteCollection("f1")

        coVerify { vectorRepository.deleteByDocument("d1") }
        coVerify { vectorRepository.deleteByDocument("d2") }
        coVerify { documentRepository.delete("d1") }
        coVerify { documentRepository.delete("d2") }
        coVerify { folderRepository.delete(folder) }
    }

    @Test
    fun `renameFolder updates folder name`() = runTest {
        val folder = Folder(id = "f1", name = "Old")
        coEvery { folderRepository.getById("f1") } returns folder
        coEvery { folderRepository.update(any()) } just Runs

        val vm = createViewModel()
        vm.renameFolder("f1", "New")

        coVerify { folderRepository.update(match { it.name == "New" }) }
    }

    @Test
    fun `renameFolder does nothing when folder not found`() = runTest {
        coEvery { folderRepository.getById("missing") } returns null

        val vm = createViewModel()
        vm.renameFolder("missing", "New")

        coVerify(exactly = 0) { folderRepository.update(any()) }
    }

    @Test
    fun `deleteFolder delegates to deleteCollection`() = runTest {
        val folder = Folder(id = "f1", name = "Test")
        val docs = listOf(Document(id = "d1", folderId = "f1", title = "Doc1", content = "a"))
        coEvery { documentRepository.getByFolderId("f1") } returns docs
        coEvery { folderRepository.getById("f1") } returns folder

        val vm = createViewModel()
        vm.deleteFolder("f1")

        coVerify { vectorRepository.deleteByDocument("d1") }
        coVerify { documentRepository.delete("d1") }
        coVerify { folderRepository.delete(folder) }
    }
}
