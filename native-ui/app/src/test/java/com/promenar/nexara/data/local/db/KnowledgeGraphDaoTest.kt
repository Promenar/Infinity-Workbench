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
}
