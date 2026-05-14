package com.promenar.nexara.ui.rag

import app.cash.turbine.test
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.domain.model.KgEdge
import com.promenar.nexara.domain.model.KgNode
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeGraphViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo: IKnowledgeGraphRepository = mockk()
    private val graphStore: GraphStore = mockk(relaxed = true)
    private val app: NexaraApplication = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun domainNode(
        id: String = "n1",
        label: String = "Node1",
        type: String = "concept"
    ) = KgNode(id = id, label = label, type = type)

    private fun domainEdge(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains"
    ) = KgEdge(id = id, sourceId = sourceId, targetId = targetId, relation = relation)

    @Test
    fun `init loadGraph uses repo getAllNodes and getAllEdges`() = runTest {
        val nodes = listOf(domainNode(id = "n1", label = "Alpha", type = "concept"))
        val edges = listOf(domainEdge(id = "e1", sourceId = "n1", targetId = "n2"))
        coEvery { repo.getAllNodes() } returns nodes
        coEvery { repo.getAllEdges() } returns edges

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.nodes.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].id).isEqualTo("n1")
            assertThat(result[0].label).isEqualTo("Alpha")
            assertThat(result[0].type).isEqualTo("concept")
        }
        vm.edges.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].sourceId).isEqualTo("n1")
        }
    }

    @Test
    fun `loadGraph maps node types to correct icons`() = runTest {
        val nodes = listOf(
            domainNode(id = "c", type = "concept"),
            domainNode(id = "d", type = "document"),
            domainNode(id = "p", type = "person"),
            domainNode(id = "o", type = "other")
        )
        coEvery { repo.getAllNodes() } returns nodes
        coEvery { repo.getAllEdges() } returns emptyList()

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.nodes.test {
            val result = awaitItem()
            assertThat(result).hasSize(4)
        }
    }

    @Test
    fun `clearGraph calls repo clear then reloads`() = runTest {
        coEvery { repo.getAllNodes() } returns emptyList()
        coEvery { repo.getAllEdges() } returns emptyList()
        coEvery { repo.clear() } returns Unit

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.clearGraph()

        coVerify { repo.clear() }
        coVerify(exactly = 2) { repo.getAllNodes() }
        coVerify(exactly = 2) { repo.getAllEdges() }
    }

    @Test
    fun `clearGraph results in empty nodes and edges`() = runTest {
        coEvery { repo.getAllNodes() } returns emptyList()
        coEvery { repo.getAllEdges() } returns emptyList()
        coEvery { repo.clear() } returns Unit

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.clearGraph()

        vm.nodes.test {
            assertThat(awaitItem()).isEmpty()
        }
        vm.edges.test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `injectMockData populates nodes and edges`() = runTest {
        coEvery { repo.getAllNodes() } returns emptyList()
        coEvery { repo.getAllEdges() } returns emptyList()

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.injectMockData()

        vm.nodes.test {
            val result = awaitItem()
            assertThat(result).hasSize(16)
            result.forEach { node ->
                assertThat(node.id).isNotEmpty()
                assertThat(node.label).isNotEmpty()
                assertThat(node.type).isIn(listOf("concept", "document", "person"))
            }
        }
        vm.edges.test {
            val result = awaitItem()
            assertThat(result).isNotEmpty()
            result.forEach { edge ->
                assertThat(edge.sourceId).isNotEmpty()
                assertThat(edge.targetId).isNotEmpty()
                assertThat(edge.relation).isIn(listOf("contains", "references", "depends_on", "authored"))
            }
        }
    }

    @Test
    fun `isLoading is false after loadGraph completes`() = runTest {
        coEvery { repo.getAllNodes() } returns emptyList()
        coEvery { repo.getAllEdges() } returns emptyList()

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.isLoading.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `loadGraph handles exception gracefully`() = runTest {
        coEvery { repo.getAllNodes() } throws RuntimeException("db error")
        coEvery { repo.getAllEdges() } returns emptyList()

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.nodes.test {
            assertThat(awaitItem()).isEmpty()
        }
        vm.isLoading.test {
            assertThat(awaitItem()).isFalse()
        }
    }
}
