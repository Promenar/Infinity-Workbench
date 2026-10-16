package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.seedKgEdge
import com.promenar.nexara.data.local.db.seedKgNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GraphSubgraphStoreTest {
    private lateinit var database: NexaraDatabase
    private lateinit var store: GraphSubgraphStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries().build()
        store = GraphSubgraphStore(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `初始读取按最近更新返回三百节点并统计全部存活实体`() = runBlocking<Unit> {
        database.withTransaction {
            (1..305).forEach { index -> database.seedKgNode(node("n-$index", index.toLong())) }
            database.kgNodeDao().insert(node("without-evidence", 99_999))
            database.seedKgNode(node("expired-document", 99_998), docId = "missing-document", workspaceDocument = true)
        }

        val result = store.load(GraphSubgraphRequest())

        assertThat(result.data.nodes).hasSize(300)
        assertThat(result.totalNodeCount).isEqualTo(305)
        assertThat(result.data.nodes.first().id).isEqualTo("n-305")
        assertThat(result.data.nodes.last().id).isEqualTo("n-6")
    }

    @Test
    fun `展开保留已显示实体并只补充有界的一跳邻居`() = runBlocking<Unit> {
        listOf("keep", "root", "a", "b", "c", "unrelated").forEachIndexed { index, id ->
            database.seedKgNode(node(id, index.toLong()))
        }
        listOf("a", "b", "c").forEach { id ->
            database.seedKgEdge(KgEdgeEntity("root-$id", "root", id, "关联", createdAt = 1))
        }

        val result = store.load(GraphSubgraphRequest(nodeLimit = 4, retainedNodeIds = listOf("keep", "root"), expandNodeId = "root"))

        assertThat(result.data.nodes.map { it.id }).containsExactly("keep", "root", "b", "c")
        assertThat(result.totalNodeCount).isEqualTo(6)
        assertThat(result.data.edges.map { it.targetId }).containsExactly("b", "c")
    }

    @Test
    fun `文档子图的总数搜索邻居与权重都只使用该文档的存活证据`() = runBlocking<Unit> {
        database.seedKgNode(node("shared", 1), docId = "doc-a")
        database.seedKgNode(node("a", 2), docId = "doc-a")
        database.seedKgNode(node("private-b", 3), docId = "doc-b")
        database.seedKgEdge(KgEdgeEntity("shared-a", "shared", "a", "关联", weight = 0.2, createdAt = 1), docId = "doc-a")
        database.seedKgEdge(KgEdgeEntity("shared-a", "shared", "a", "关联", weight = 0.9, createdAt = 1), docId = "doc-b")
        database.seedKgEdge(KgEdgeEntity("private-edge", "shared", "private-b", "私有", createdAt = 1), docId = "doc-b")

        val result = store.load(GraphSubgraphRequest(docId = "doc-a", retainedNodeIds = listOf("shared"), expandNodeId = "shared"))
        val search = store.search(GraphEntitySearchRequest("private", docId = "doc-a"))

        assertThat(result.totalNodeCount).isEqualTo(2)
        assertThat(result.data.nodes.map { it.id }).containsExactly("shared", "a")
        assertThat(result.data.edges.single().weight).isWithin(0.0001).of(0.2)
        assertThat(result.data.edges.single().documentIds).containsExactly("doc-a")
        assertThat(search).isEmpty()
    }

    @Test
    fun `实体搜索规范化中英文并把百分号当作文字`() = runBlocking<Unit> {
        database.seedKgNode(node("english", 1, "Ｋｏｔｌｉｎ"))
        database.seedKgNode(node("chinese", 2, "人工智能"))
        database.seedKgNode(node("percent", 3, "100% literal"))
        database.seedKgNode(node("other", 4))

        assertThat(store.search(GraphEntitySearchRequest("KOT")).map { it.id }).containsExactly("english")
        assertThat(store.search(GraphEntitySearchRequest("工智")).map { it.id }).containsExactly("chinese")
        assertThat(store.search(GraphEntitySearchRequest("%")).map { it.id }).containsExactly("percent")
        assertThat(store.search(GraphEntitySearchRequest(""))).isEmpty()
    }

    @Test
    fun `超过SQLite单次绑定上限的保留集合也遵循两千实体硬上限`() = runBlocking<Unit> {
        database.withTransaction {
            (1..2_050).forEach { database.seedKgNode(node("n-$it", it.toLong())) }
        }

        val result = store.load(GraphSubgraphRequest(nodeLimit = 10_000, retainedNodeIds = (1..2_050).map { "n-$it" }))

        assertThat(result.data.nodes).hasSize(2_000)
        assertThat(result.totalNodeCount).isEqualTo(2_050)
    }

    @Test
    fun `内部关系按边预算裁剪并始终具有已显示端点`() = runBlocking<Unit> {
        listOf("a", "b", "c", "outside").forEachIndexed { index, id -> database.seedKgNode(node(id, index.toLong())) }
        listOf("b", "c", "outside").forEach { id ->
            database.seedKgEdge(KgEdgeEntity("a-$id", "a", id, "关联", createdAt = 1))
        }

        val result = store.load(GraphSubgraphRequest(nodeLimit = 3, edgeLimit = 1, retainedNodeIds = listOf("a", "b", "c")))

        assertThat(result.data.edges).hasSize(1)
        assertThat(result.data.edges.single().sourceId).isEqualTo("a")
        assertThat(result.data.edges.single().targetId).isAnyOf("b", "c")
    }

    private fun node(id: String, updatedAt: Long, name: String = id) = KgNodeEntity(id, name, createdAt = 1, updatedAt = updatedAt)
}
