package com.promenar.nexara.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.manager.KgContextResult
import com.promenar.nexara.ui.chat.manager.KgProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PersistentGraphRetrievalTest {
    private lateinit var db: NexaraDatabase
    private lateinit var store: GraphStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        store = GraphStore(db)
    }
    @After fun close() = db.close()

    @Test fun `JIT仅消费当前会话可核验的记忆片段`() = runBlocking<Unit> {
        for (session in listOf("s", "other")) db.sessionDao().insert(com.promenar.nexara.data.local.db.entity.SessionEntity(
            session, "agent", createdAt = 1, updatedAt = 1))
        for ((id, session) in listOf("own" to "s", "hidden" to "other")) db.vectorDao().insert(
            com.promenar.nexara.data.local.db.entity.VectorEntity(id, sessionId = session,
                content = "片段-$id", embedding = byteArrayOf(), createdAt = 1))
        var consumed = emptyList<String>()
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                consumed = topKResults.map { it.id }
                return KgContextResult("")
            }
        }
        PersistentGraphKgProvider(store, jit) { RagConfiguration() }.retrieveContext("无覆盖", "s",
            listOf(RagReference("own", "片段-own"), RagReference("hidden", "片段-hidden"), RagReference("missing", "无来源")),
            RagOptions(enableKnowledgeGraph = true))
        assertThat(consumed).containsExactly("own")
    }

    @Test fun `大选择集不截断且来源ID不成为SQL语法`() = runBlocking<Unit> {
        val special = "文档,'); DROP TABLE kg_nodes; --"
        val docs = (0..1200).map { "doc-$it" } + special
        val scope = KgScope(docId = special)
        val a = store.upsertNode("边界实体", scope = scope)
        val b = store.upsertNode("目标实体", scope = scope)
        store.createEdge(a, b, "边界关系", scope = scope)
        val retrieval = GraphRetrievalStore(db)
        val selected = KgRetrievalScope("s", docs)
        assertThat(retrieval.visibleDocumentIds(selected)).containsAtLeast("doc-400", "doc-1200", special)
        val paths = retrieval.retrieve("边界实体", selected).paths
        assertThat(paths).hasSize(1)
        assertThat(paths.single().sourceDocumentIds).containsExactly(special)
        assertThat(db.kgNodeDao().getCount()).isEqualTo(2)
    }

    @Test fun `第129个文件夹子文档可见`() = runBlocking<Unit> {
        val folders = (0..128).map { "folder-$it" }
        db.fileEntryDao().insert(com.promenar.nexara.data.local.db.entity.FileEntry(
            uuid = folders.last(), workspaceRootUuid = "root", parentUuid = "root", name = "folder", hash = "h",
            physicalRootPath = "/tmp/kg-scope-fixture", materializedPath = "/folder", isDirectory = true, createdAt = 1, updatedAt = 1))
        db.fileEntryDao().insert(com.promenar.nexara.data.local.db.entity.FileEntry(
            uuid = "last-folder-doc", workspaceRootUuid = "root", parentUuid = folders.last(), name = "doc.md", hash = "h",
            physicalRootPath = "/tmp/kg-scope-fixture", materializedPath = "/folder/doc.md", createdAt = 1, updatedAt = 1))
        assertThat(GraphRetrievalStore(db).visibleDocumentIds(KgRetrievalScope("s", folderIds = folders)))
            .contains("last-folder-doc")
    }

    @Test fun `跨选择边界的联合JIT要求全部依赖可见`() = runBlocking<Unit> {
        val docs = (0..400).map { "joint-$it" }
        val first = KgScope(sessionId = "s", docId = docs.first(), taskId = "joint-task")
        val last = first.copy(docId = docs.last())
        val a = store.upsertNode("联合甲", scope = first, sourceType = "jit")
        val b = store.upsertNode("联合乙", scope = first, sourceType = "jit")
        store.createEdge(a, b, "联合关系", scope = first, sourceType = "jit")
        store.createEdge(a, b, "联合关系", scope = last, sourceType = "jit")
        val retrieval = GraphRetrievalStore(db)
        assertThat(retrieval.retrieve("联合甲", KgRetrievalScope("s", docs)).paths).hasSize(1)
        assertThat(retrieval.retrieve("联合甲", KgRetrievalScope("s", docs.dropLast(1))).paths).isEmpty()
    }

    @Test fun `索引种子匹配中英文大小写包含与分词`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        store.upsertNode("北京大学", scope = scope)
        store.upsertNode("Ａlice", "person", scope = scope)
        store.upsertNode("Knowledge Graph", scope = scope)
        val retrieval = GraphRetrievalStore(db)
        for (query in listOf("北京", "请介绍北京大学", "ALICE", "graph 技术")) {
            assertThat(retrieval.retrieve(query, KgRetrievalScope("s")).seedCount).isAtLeast(1)
        }
        val plan = retrieval.explainSeedQuery("graph", KgRetrievalScope("s"))
        assertThat(plan.none { it.contains("SCAN n") }).isTrue()
        assertThat(plan.any { it.contains("INDEX") }).isTrue()
    }

    @Test fun `一二跳扩展上限和会话证据隔离`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        val a = store.upsertNode("甲", scope = scope)
        val b = store.upsertNode("乙", scope = scope)
        val c = store.upsertNode("丙", scope = scope)
        val hidden = store.upsertNode("私有", scope = KgScope(sessionId = "other"))
        store.createEdge(a, b, "一跳", scope = scope)
        store.createEdge(b, c, "二跳", scope = scope)
        store.createEdge(a, hidden, "秘密关系", scope = KgScope(sessionId = "other"))
        val retrieval = GraphRetrievalStore(db)
        assertThat(retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 1)).paths.map { it.edges.single().relation })
            .containsExactly("一跳")
        val two = retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 2))
        assertThat(two.paths.map { it.edges.single().relation }).containsExactly("一跳", "二跳")
        assertThat(two.paths.map { it.distance }).containsExactly(1, 2)
        val bounded = retrieval.retrieve("甲", KgRetrievalScope("s"), config = RagConfiguration(kgRetrievalHops = 2, kgMaxNodes = 2, kgMaxEdges = 1, kgMaxSeeds = 1))
        assertThat(bounded.paths).hasSize(1)
        assertThat(bounded.seedCount).isEqualTo(1)
        assertThat(retrieval.retrieve("私有", KgRetrievalScope("s")).seedCount).isEqualTo(0)
    }

    @Test fun `文档种子与当前可见证据置信度不借用其它会话`() = runBlocking<Unit> {
        val a = store.upsertNode("甲", scope = KgScope(docId = "visible"))
        val b = store.upsertNode("乙", scope = KgScope(docId = "visible"))
        store.createEdge(a, b, "关系", docId = "visible", weight = 0.2)
        store.createEdge(a, b, "关系", scope = KgScope(sessionId = "other"), weight = 0.99)
        val found = GraphRetrievalStore(db).retrieve("无词面命中", KgRetrievalScope("s", listOf("visible")), listOf("visible"))
        assertThat(found.seedCount).isEqualTo(2)
        assertThat(found.paths.single().edges.single().weight).isEqualTo(0.2)
        assertThat(found.paths.single().sourceDocumentIds).containsExactly("visible")
        Unit
    }

    @Test fun `持久图充足不调用JIT不足才调用且可关闭`() = runBlocking<Unit> {
        val scope = KgScope(sessionId = "s")
        val a = store.upsertNode("甲", scope = scope)
        val b = store.upsertNode("乙", scope = scope)
        store.createEdge(a, b, "关系", scope = scope)
        var calls = 0
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                calls++
                return KgContextResult("", jitExtractionCount = 1, jitModel = "p::kg")
            }
        }
        val options = RagOptions(activeDocIds = listOf("doc"), enableKnowledgeGraph = true)
        val refs = listOf(RagReference("r", "片段", documentId = "doc"))
        val provider = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitMinSeeds = 1) }
        val persistentOnly = provider.retrieveContext("甲", "s", refs, options)
        assertThat(calls).isEqualTo(0)
        assertThat(persistentOnly.jitExtractionCount).isEqualTo(0)
        // 临时抽取的次数与模型交给检索轨迹展示。
        val withJit = provider.retrieveContext("不存在", "s", refs, options)
        assertThat(calls).isEqualTo(1)
        assertThat(withJit.jitExtractionCount to withJit.jitModel).isEqualTo(1 to "p::kg")
        val disabled = PersistentGraphKgProvider(store, jit) { RagConfiguration(kgJitFallbackEnabled = false) }
        disabled.retrieveContext("不存在", "s", refs, options)
        assertThat(calls).isEqualTo(1)
    }
}
