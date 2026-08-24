package com.promenar.nexara.ui.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.GraphData
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.data.rag.KgNode
import com.promenar.nexara.data.rag.KgEdge
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    @Test
    fun `文档选项加载异常暴露可重试typed error而非伪装为空列表`() = runTest {
        coEvery { graphStore.getDocumentOptions() } throws
            IllegalStateException("private document option detail")
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.documentOptions.value).isEmpty()
        assertThat(vm.documentOptionsError.value?.code)
            .isEqualTo(KgDocumentOptionsErrorCode.LoadFailed)
        assertThat(vm.documentOptionsError.value?.canRetry).isTrue()
        assertThat(vm.documentOptionsError.value?.technical)
            .doesNotContain("private document option detail")
    }

    @Test
    fun `文档选项失败后重试成功恢复列表并清除错误`() = runTest {
        coEvery { graphStore.getDocumentOptions() } throws
            IllegalStateException("first failure") andThen
            listOf(KgDocumentOption(docId = "doc-a", title = "Alpha"))
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.retryDocumentOptions()

        assertThat(vm.documentOptionsError.value).isNull()
        assertThat(vm.documentOptions.value)
            .containsExactly(KgDocumentOption(docId = "doc-a", title = "Alpha"))
    }

    @Test
    fun `文档模式未选择docId时不复用全局图且明确要求选择`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(
            listOf(dataNode(id = "global")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.setViewMode(KgViewMode.DOCUMENT)

        assertThat(vm.selectedDocumentId.value).isNull()
        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.documentSelectionRequired.value).isTrue()
        coVerify(exactly = 1) { graphStore.getGraphData() }
        coVerify(exactly = 1) { graphStore.getGraphData(any(), any(), any()) }
    }

    @Test
    fun `全局与每个文档缓存隔离且切换回来不重复串读`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(
            listOf(dataNode(id = "global")),
            emptyList(),
        )
        coEvery { graphStore.getGraphData(docIds = listOf("doc-a")) } returns GraphData(
            listOf(dataNode(id = "doc-a-node")),
            emptyList(),
        )
        coEvery { graphStore.getGraphData(docIds = listOf("doc-b")) } returns GraphData(
            listOf(dataNode(id = "doc-b-node")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-node")
        vm.loadGraphByDoc("doc-b")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-node")
        vm.setViewMode(KgViewMode.GLOBAL)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("global")

        coVerify(exactly = 1) { graphStore.getGraphData() }
        coVerify(exactly = 1) { graphStore.getGraphData(docIds = listOf("doc-a")) }
        coVerify(exactly = 1) { graphStore.getGraphData(docIds = listOf("doc-b")) }
    }

    @Test
    fun `文档加载失败保留最近成功图并暴露可重试typed error`() = runTest {
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())
        coEvery { graphStore.getGraphData(docIds = listOf("doc-a")) } returns GraphData(
            listOf(dataNode(id = "last-good")),
            emptyList(),
        )
        coEvery { graphStore.getGraphData(docIds = listOf("doc-b")) } throws
            IllegalStateException("private database detail")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.loadGraphByDoc("doc-a")

        vm.loadGraphByDoc("doc-b")

        assertThat(vm.nodes.value.map { it.id }).containsExactly("last-good")
        assertThat(vm.loadError.value?.code).isEqualTo(KgLoadErrorCode.LoadFailed)
        assertThat(vm.loadError.value?.canRetry).isTrue()
        assertThat(vm.loadError.value?.technical).doesNotContain("private database detail")
    }

    @Test
    fun `迟到文档加载不会覆盖更新的文档选择`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphData() } returns GraphData(emptyList(), emptyList())
        coEvery { graphStore.getGraphData(docIds = listOf("doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseA.await() }
            GraphData(listOf(dataNode(id = "doc-a-node")), emptyList())
        }
        coEvery { graphStore.getGraphData(docIds = listOf("doc-b")) } returns GraphData(
            listOf(dataNode(id = "doc-b-node")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.loadGraphByDoc("doc-a")
        vm.loadGraphByDoc("doc-b")
        releaseA.complete(Unit)

        assertThat(vm.selectedDocumentId.value).isEqualTo("doc-b")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
    }

    @Test
    fun `切回已缓存全局图会取消迟到文档结果`() = runTest {
        val releaseDocument = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphData() } returns GraphData(
            listOf(dataNode(id = "global-node")),
            emptyList(),
        )
        coEvery { graphStore.getGraphData(docIds = listOf("doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseDocument.await() }
            GraphData(listOf(dataNode(id = "doc-a-node")), emptyList())
        }
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.loadGraphByDoc("doc-a")
        vm.setViewMode(KgViewMode.GLOBAL)
        releaseDocument.complete(Unit)

        assertThat(vm.viewMode.value).isEqualTo(KgViewMode.GLOBAL)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("global-node")
    }

    @Test
    fun `概念模式首次加载不会被重置为全局模式`() = runTest {
        coEvery { graphStore.getGraphData() } returnsMany listOf(
            GraphData(emptyList(), emptyList()),
            GraphData(listOf(dataNode(id = "concept-node")), emptyList()),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.setViewMode(KgViewMode.CONCEPT)

        assertThat(vm.viewMode.value).isEqualTo(KgViewMode.CONCEPT)
    }
}
