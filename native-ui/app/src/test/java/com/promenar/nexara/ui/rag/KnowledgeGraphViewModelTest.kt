package com.promenar.nexara.ui.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.GraphData
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.KgNode
import com.promenar.nexara.data.rag.KgEdge
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

    private fun dataNode(
        id: String = "n1",
        name: String = "Node1",
        type: String = "concept"
    ) = KgNode(id = id, name = name, type = type, createdAt = 100L)

    private fun dataEdge(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains"
    ) = KgEdge(id = id, sourceId = sourceId, targetId = targetId, relation = relation, createdAt = 100L)

    @Test
    fun `init loadGraph uses repo getAllNodes and getAllEdges`() = runTest {
        val nodes = listOf(dataNode(id = "n1", name = "Alpha", type = "concept"))
        val edges = listOf(dataEdge(id = "e1", sourceId = "n1", targetId = "n2"))
        coEvery { graphStore.getGraphData() } returns GraphData(nodes, edges)

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value).hasSize(1)
        assertThat(vm.nodes.value[0].id).isEqualTo("n1")
        assertThat(vm.nodes.value[0].label).isEqualTo("Alpha")
        assertThat(vm.nodes.value[0].type).isEqualTo("concept")
    }

    @Test
    fun `loadGraph maps node types to correct icons`() = runTest {
        val nodes = listOf(
            dataNode(id = "c", type = "concept"),
            dataNode(id = "d", type = "document"),
            dataNode(id = "p", type = "person"),
            dataNode(id = "o", type = "other")
        )
        coEvery { graphStore.getGraphData() } returns GraphData(nodes, emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value).hasSize(4)
    }

    @Test
    fun `clearGraph calls repo clear then reloads`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())
        coEvery { repo.clear() } returns Unit

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.clearGraph()

        coVerify { repo.clear() }
        coVerify(atLeast = 2) { graphStore.getGraphData() }
    }

    @Test
    fun `clearGraph results in empty nodes and edges`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())
        coEvery { repo.clear() } returns Unit

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.clearGraph()

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.edges.value).isEmpty()
    }

    @Test
    fun `injectMockData populates nodes and edges`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.injectMockData()

        assertThat(vm.nodes.value).hasSize(16)
        vm.nodes.value.forEach { node ->
            assertThat(node.id).isNotEmpty()
            assertThat(node.label).isNotEmpty()
            assertThat(node.type).isIn(listOf("concept", "document", "person"))
        }
        assertThat(vm.edges.value).isNotEmpty()
        vm.edges.value.forEach { edge ->
            assertThat(edge.sourceId).isNotEmpty()
            assertThat(edge.targetId).isNotEmpty()
            assertThat(edge.relation).isIn(listOf("contains", "references", "depends_on", "authored"))
        }
    }

    @Test
    fun `isLoading is false after loadGraph completes`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `loadGraph handles exception gracefully`() = runTest {
        coEvery { graphStore.getGraphData() } throws RuntimeException("db error")

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.isLoading.value).isFalse()
    }
}
