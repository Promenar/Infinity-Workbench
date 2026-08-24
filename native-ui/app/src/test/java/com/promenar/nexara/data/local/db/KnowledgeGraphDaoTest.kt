package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class KnowledgeGraphDaoTest {
    private lateinit var db: NexaraDatabase
    private lateinit var nodeDao: KgNodeDao
    private lateinit var edgeDao: KgEdgeDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        nodeDao = db.kgNodeDao()
        edgeDao = db.kgEdgeDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetNode() = runBlocking {
        val node = KgNodeEntity(
            id = "n1", name = "Kotlin", type = "concept",
            metadata = """{"lang":"JVM"}""", sessionId = "s1",
            sourceType = "full", createdAt = 1000L,
        )
        nodeDao.insert(node)
        val result = nodeDao.getById("n1")
        assertThat(result).isNotNull()
        assertThat(result!!.name).isEqualTo("Kotlin")
        assertThat(result.type).isEqualTo("concept")
    }

    @Test
    fun getByName() = runBlocking {
        nodeDao.insert(KgNodeEntity(id = "n1", name = "Kotlin", createdAt = 1000L))
        val result = nodeDao.getByName("Kotlin")
        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo("n1")
    }

    @Test
    fun getBySessionId() = runBlocking {
        nodeDao.insert(KgNodeEntity(id = "n1", name = "A", sessionId = "s1", createdAt = 1000L))
        nodeDao.insert(KgNodeEntity(id = "n2", name = "B", sessionId = "s1", createdAt = 2000L))
        nodeDao.insert(KgNodeEntity(id = "n3", name = "C", sessionId = "s2", createdAt = 3000L))
        val result = nodeDao.getBySessionId("s1")
        assertThat(result).hasSize(2)
    }

    @Test
    fun insertAndGetEdge() = runBlocking {
        nodeDao.insert(KgNodeEntity(id = "n1", name = "Kotlin", createdAt = 1000L))
        nodeDao.insert(KgNodeEntity(id = "n2", name = "Android", createdAt = 1000L))
        val edge = KgEdgeEntity(
            id = "e1", sourceId = "n1", targetId = "n2",
            relation = "used_for", weight = 0.9,
            sessionId = "s1", createdAt = 1000L,
        )
        edgeDao.insert(edge)
        val result = edgeDao.getById("e1")
        assertThat(result).isNotNull()
        assertThat(result!!.relation).isEqualTo("used_for")
        assertThat(result.weight).isWithin(0.001).of(0.9)
    }

    @Test
    fun getByNodeId() = runBlocking {
        nodeDao.insert(KgNodeEntity(id = "n1", name = "A", createdAt = 1000L))
        nodeDao.insert(KgNodeEntity(id = "n2", name = "B", createdAt = 1000L))
        nodeDao.insert(KgNodeEntity(id = "n3", name = "C", createdAt = 1000L))
        edgeDao.insert(KgEdgeEntity(id = "e1", sourceId = "n1", targetId = "n2", relation = "r1", createdAt = 1000L))
        edgeDao.insert(KgEdgeEntity(id = "e2", sourceId = "n3", targetId = "n1", relation = "r2", createdAt = 1000L))
        val edges = edgeDao.getByNodeId("n1")
        assertThat(edges).hasSize(2)
    }

    @Test
    fun deleteBySessionId() = runBlocking {
        nodeDao.insert(KgNodeEntity(id = "n1", name = "A", sessionId = "s1", createdAt = 1000L))
        nodeDao.insert(KgNodeEntity(id = "n2", name = "B", sessionId = "s1", createdAt = 1000L))
        edgeDao.insert(KgEdgeEntity(id = "e1", sourceId = "n1", targetId = "n2", relation = "r", sessionId = "s1", createdAt = 1000L))
        edgeDao.deleteBySessionId("s1")
        assertThat(edgeDao.getById("e1")).isNull()
        val nodes = nodeDao.getBySessionId("s1")
        assertThat(nodes).isNotEmpty()
    }

    @Test
    fun `永久删除已回收legacy文档会清除专属孤立节点并保留共享与全局节点`() = runBlocking {
        val root = FileEntry(
            uuid = "root",
            workspaceRootUuid = "root",
            parentUuid = null,
            name = "root",
            hash = "root-hash",
            isDirectory = true,
            physicalRootPath = "/tmp/root",
            materializedPath = "/",
            createdAt = 1,
            updatedAt = 1,
        )
        val recycled = FileEntry(
            uuid = "recycled-doc",
            workspaceRootUuid = root.uuid,
            parentUuid = root.uuid,
            name = "old.md",
            hash = "old-hash",
            physicalRootPath = "/tmp/root",
            materializedPath = "/.recycle_bin/recycled-doc",
            inRecycleBin = true,
            originalParentUuid = root.uuid,
            originalMaterializedPath = "/old.md",
            createdAt = 1,
            updatedAt = 2,
        )
        val active = recycled.copy(
            uuid = "active-doc",
            name = "active.md",
            materializedPath = "/active.md",
            inRecycleBin = false,
            originalParentUuid = null,
            originalMaterializedPath = null,
        )
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(recycled)
        db.fileEntryDao().insert(active)
        listOf(
            KgNodeEntity("legacy-a", "Legacy A", createdAt = 1),
            KgNodeEntity("legacy-b", "Legacy B", createdAt = 1),
            KgNodeEntity("shared", "Shared", createdAt = 1),
            KgNodeEntity("active-target", "Active", createdAt = 1),
            KgNodeEntity("global", "Global", createdAt = 1),
        ).forEach { nodeDao.insert(it) }
        edgeDao.insert(
            KgEdgeEntity(
                id = "legacy-only",
                sourceId = "legacy-a",
                targetId = "legacy-b",
                relation = "old",
                docId = recycled.uuid,
                fileUuid = null,
                createdAt = 1,
            ),
        )
        edgeDao.insert(
            KgEdgeEntity(
                id = "legacy-shared",
                sourceId = "shared",
                targetId = "legacy-b",
                relation = "old-shared",
                docId = recycled.uuid,
                fileUuid = null,
                createdAt = 1,
            ),
        )
        edgeDao.insert(
            KgEdgeEntity(
                id = "active-shared",
                sourceId = "shared",
                targetId = "active-target",
                relation = "active",
                docId = active.uuid,
                fileUuid = null,
                createdAt = 1,
            ),
        )

        WorkspaceDeletionTransaction(db).delete(root.uuid, listOf(recycled.uuid))

        assertThat(rawNodeCount("legacy-a")).isEqualTo(0)
        assertThat(rawNodeCount("legacy-b")).isEqualTo(0)
        assertThat(nodeDao.getById("legacy-a")).isNull()
        assertThat(nodeDao.getById("legacy-b")).isNull()
        assertThat(nodeDao.getById("shared")).isNotNull()
        assertThat(nodeDao.getById("active-target")).isNotNull()
        assertThat(nodeDao.getById("global")).isNotNull()
        assertThat(edgeDao.getById("legacy-only")).isNull()
        assertThat(edgeDao.getById("legacy-shared")).isNull()
        assertThat(edgeDao.getById("active-shared")).isNotNull()
    }

    @Test
    fun `文档选择列表只包含active工作区文件且使用文件标题`() = runBlocking {
        val root = FileEntry(
            uuid = "option-root",
            workspaceRootUuid = "option-root",
            parentUuid = null,
            name = "root",
            hash = "root-hash",
            isDirectory = true,
            physicalRootPath = "/tmp/option-root",
            materializedPath = "/",
            createdAt = 1,
            updatedAt = 1,
        )
        val active = FileEntry(
            uuid = "option-active",
            workspaceRootUuid = root.uuid,
            parentUuid = root.uuid,
            name = "Active title.md",
            hash = "active-hash",
            physicalRootPath = root.physicalRootPath,
            materializedPath = "/Active title.md",
            createdAt = 1,
            updatedAt = 1,
        )
        val recycled = active.copy(
            uuid = "option-recycled",
            name = "Recycled.md",
            materializedPath = "/.recycle_bin/option-recycled",
            inRecycleBin = true,
        )
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(active)
        db.fileEntryDao().insert(recycled)
        nodeDao.insert(KgNodeEntity(id = "option-source", name = "S", createdAt = 1))
        nodeDao.insert(KgNodeEntity(id = "option-target", name = "T", createdAt = 1))
        listOf(
            "active-edge" to active.uuid,
            "recycled-edge" to recycled.uuid,
            "non-file-edge" to "legacy-session-doc",
        ).forEach { (id, docId) ->
            edgeDao.insert(
                KgEdgeEntity(
                    id = id,
                    sourceId = "option-source",
                    targetId = "option-target",
                    relation = "r",
                    docId = docId,
                    createdAt = 1,
                ),
            )
        }

        val options = edgeDao.getActiveDocumentOptions()

        assertThat(options.map { it.docId }).containsExactly(active.uuid)
        assertThat(options.single().title).isEqualTo(active.name)
    }

    private fun rawNodeCount(nodeId: String): Int {
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM kg_nodes WHERE id = ?",
            arrayOf(nodeId),
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }
}
