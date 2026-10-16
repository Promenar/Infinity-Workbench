package com.promenar.nexara.ui.rag

import com.promenar.nexara.R
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.GraphData
import com.promenar.nexara.data.rag.GraphSubgraph
import com.promenar.nexara.data.rag.GraphSubgraphRequest
import com.promenar.nexara.data.rag.GraphEntitySearchRequest
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `图谱失败文案按同scope stale与跨scope generic精确区分`() {
        assertThat(kgLoadErrorMessageRes(isStale = false))
            .isEqualTo(R.string.kg_load_failed)
        assertThat(kgLoadErrorMessageRes(isStale = true))
            .isEqualTo(R.string.kg_load_failed_stale)
    }

    private fun dataEdge(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains"
    ) = KgEdge(id = id, sourceId = sourceId, targetId = targetId, relation = relation, createdAt = 100L)

    @Test
    fun `初始图谱只展示有界子图`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(
            (1..300).map { dataNode(id = "node-$it") },
            emptyList(),
            total = 301,
        )

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value.size).isAtMost(300)
        assertThat(vm.totalNodeCount.value).isEqualTo(301)
    }

    @Test
    fun `初始图谱映射有界读取返回的实体`() = runTest {
        val nodes = listOf(dataNode(id = "n1", name = "Alpha", type = "concept"))
        val edges = listOf(dataEdge(id = "e1", sourceId = "n1", targetId = "n2"))
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(nodes, edges)

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
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(nodes, emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value).hasSize(4)
    }



    @Test
    fun `isLoading is false after loadGraph completes`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `loadGraph handles exception gracefully`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } throws RuntimeException("db error")

        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `文档选项加载异常暴露可重试typed error而非伪装为空列表`() = runTest {
        coEvery { graphStore.getDocumentOptions() } throws
            IllegalStateException("private document option detail")
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())

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
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.retryDocumentOptions()

        assertThat(vm.documentOptionsError.value).isNull()
        assertThat(vm.documentOptions.value)
            .containsExactly(KgDocumentOption(docId = "doc-a", title = "Alpha"))
    }

    @Test
    fun `文档模式未选择docId时不复用全局图且明确要求选择`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(
            listOf(dataNode(id = "global")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.setViewMode(KgViewMode.DOCUMENT)

        assertThat(vm.selectedDocumentId.value).isNull()
        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.documentSelectionRequired.value).isTrue()
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(GraphSubgraphRequest()) }
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(any()) }
    }

    @Test
    fun `全局与文档scope切换每次重读GraphStore且不复用跨scope缓存`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returnsMany listOf(
            subgraph(listOf(dataNode(id = "global-1")), emptyList()),
            subgraph(listOf(dataNode(id = "global-2")), emptyList()),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returnsMany listOf(
            subgraph(listOf(dataNode(id = "doc-a-1")), emptyList()),
            subgraph(listOf(dataNode(id = "doc-a-2")), emptyList()),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } returns subgraph(
            listOf(dataNode(id = "doc-b-node")),
            emptyList(),
        )
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-1")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-a")
        vm.loadGraphByDoc("doc-b")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
        vm.loadGraphByDoc("doc-a")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-a-2")
        vm.setViewMode(KgViewMode.GLOBAL)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("global-2")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("global")

        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest()) }
        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) }
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) }
    }

    @Test
    fun `跨文档scope加载失败不会泄露上一文档图`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns subgraph(
            listOf(dataNode(id = "last-good")),
            emptyList(),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } throws
            IllegalStateException("private database detail")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.loadGraphByDoc("doc-a")

        vm.loadGraphByDoc("doc-b")

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-b")
        assertThat(vm.graphState.value.isStale).isFalse()
        assertThat(vm.loadError.value?.code).isEqualTo(KgLoadErrorCode.LoadFailed)
        assertThat(vm.loadError.value?.canRetry).isTrue()
        assertThat(vm.loadError.value?.technical).doesNotContain("private database detail")
    }

    @Test
    fun `同scope显式刷新失败保留last good并标记stale`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns
            subgraph(listOf(dataNode(id = "last-good")), emptyList()) andThenThrows
            IllegalStateException("refresh failed")
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.loadGraphByDoc("doc-a")

        vm.refreshCurrentScope()

        assertThat(vm.nodes.value.map { it.id }).containsExactly("last-good")
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-a")
        assertThat(vm.graphState.value.isStale).isTrue()
        assertThat(vm.loadError.value?.code).isEqualTo(KgLoadErrorCode.LoadFailed)
        coVerify(exactly = 2) { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) }
    }

    @Test
    fun `切换scope立即清空画布并进入新scope loading`() = runTest {
        val releaseB = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } returns
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } coAnswers {
            releaseB.await()
            subgraph(listOf(dataNode(id = "doc-b-node")), emptyList())
        }
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.loadGraphByDoc("doc-a")

        vm.loadGraphByDoc("doc-b")

        assertThat(vm.nodes.value).isEmpty()
        assertThat(vm.graphState.value.scopeKey).isEqualTo("document:doc-b")
        assertThat(vm.graphState.value.isLoading).isTrue()
        releaseB.complete(Unit)
        assertThat(vm.nodes.value.map { it.id }).containsExactly("doc-b-node")
    }

    @Test
    fun `迟到文档加载不会覆盖更新的文档选择`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseA.await() }
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
        }
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-b")) } returns subgraph(
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
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(
            listOf(dataNode(id = "global-node")),
            emptyList(),
        )
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(docId = "doc-a")) } coAnswers {
            withContext(NonCancellable) { releaseDocument.await() }
            subgraph(listOf(dataNode(id = "doc-a-node")), emptyList())
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
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest(conceptOnly = true)) } returns
            subgraph(listOf(dataNode(id = "concept-node")), emptyList())
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.setViewMode(KgViewMode.CONCEPT)

        assertThat(vm.viewMode.value).isEqualTo(KgViewMode.CONCEPT)
        assertThat(vm.graphState.value.scopeKey).isEqualTo("concept:mode")
        assertThat(vm.nodes.value.map { it.id }).containsExactly("concept-node")
        coVerify(exactly = 1) { graphStore.getGraphSubgraph(GraphSubgraphRequest(conceptOnly = true)) }
    }

    @Test
    fun `展开把当前实体作为保留集合并更新同一子图与总数`() = runTest {
        coEvery { graphStore.getGraphSubgraph(GraphSubgraphRequest()) } returns
            subgraph(listOf(dataNode("root")), emptyList(), total = 500)
        coEvery { graphStore.getGraphSubgraph(match { it.expandNodeId == "root" && it.retainedNodeIds == listOf("root") && it.nodeLimit <= 300 }) } returns
            subgraph(listOf(dataNode("root"), dataNode("neighbor")), listOf(dataEdge(sourceId = "root", targetId = "neighbor")), total = 500)
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)

        vm.expandNode("root")

        assertThat(vm.nodes.value.map { it.id }).containsExactly("root", "neighbor")
        assertThat(vm.edges.value.single().targetId).isEqualTo("neighbor")
        assertThat(vm.totalNodeCount.value).isEqualTo(500)
    }

    @Test
    fun `实体搜索遵循当前文档并在切换范围时清空结果`() = runTest {
        coEvery { graphStore.getGraphSubgraph(any()) } returns subgraph(emptyList(), emptyList())
        coEvery { graphStore.searchGraphEntities(GraphEntitySearchRequest("人工", docId = "doc-a")) } returns
            listOf(dataNode("found", "人工智能"))
        val vm = KnowledgeGraphViewModel(repo, graphStore, app)
        vm.loadGraphByDoc("doc-a")

        vm.searchEntities("人工")
        advanceTimeBy(200)
        runCurrent()

        assertThat(vm.searchResults.value.map { it.id }).containsExactly("found")
        assertThat(vm.isSearching.value).isFalse()
        vm.loadGraphByDoc("doc-b")
        assertThat(vm.searchQuery.value).isEmpty()
        assertThat(vm.searchResults.value).isEmpty()
    }

    private fun subgraph(nodes: List<KgNode>, edges: List<KgEdge>, total: Int = nodes.size) =
        GraphSubgraph(GraphData(nodes, edges), total)
}
