package com.promenar.nexara.ui.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

    private lateinit var app: NexaraApplication
    private lateinit var vectorDao: VectorDao

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorDao = mockk(relaxed = true)
        val database = mockk<NexaraDatabase>(relaxed = true)
        app = mockk<NexaraApplication>(relaxed = true)

        every { app.database } returns database
        every { database.folderDao() } returns mockk(relaxed = true)
        every { database.vectorDao() } returns vectorDao
        every { app.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        every { app.vectorizationQueue } returns mockk(relaxed = true)
        every { app.documentImporter } returns mockk(relaxed = true)

        coEvery { documentRepository.getCount() } returns 0
        coEvery { kgRepository.getNodeCount() } returns 0
        every { documentRepository.observeAll() } returns flowOf(emptyList())
        coEvery { vectorDao.getCount() } returns 0
        coEvery { vectorDao.countByType() } returns emptyList()
        coEvery { vectorDao.countBySession(any()) } returns emptyList()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): RagViewModel =
        RagViewModel(app, documentRepository, vectorRepository, kgRepository)

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
        assertThat(vm.searchResults.value.map { it.id }).containsExactly("d1", "d3")
    }

    @Test
    fun `search clears results on blank query`() = runTest {
        val vm = createViewModel()
        vm.search("  ")

        assertThat(vm.searchResults.value).isEmpty()
    }
}
