package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.rag.GraphExtractor
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KnowledgeGraphRepositoryTest {

    private val kgNodeDao: KgNodeDao = mockk()
    private val kgEdgeDao: KgEdgeDao = mockk()
    private val graphExtractor: GraphExtractor = mockk()

    private val repo = KnowledgeGraphRepository(
        kgNodeDao = kgNodeDao,
        kgEdgeDao = kgEdgeDao,
        graphExtractor = graphExtractor
    )

    private fun nodeEntity(
        id: String = "n1",
        name: String = "Node1",
        type: String = "concept",
        createdAt: Long = 1000L
    ) = KgNodeEntity(id = id, name = name, type = type, createdAt = createdAt)

    private fun edgeEntity(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains",
        createdAt: Long = 1000L
    ) = KgEdgeEntity(id = id, sourceId = sourceId, targetId = targetId, relation = relation, createdAt = createdAt)

    @Test
    fun `getAllNodes returns from dao`() = runTest {
        val entities = listOf(
            nodeEntity(id = "n1", name = "Alpha", type = "concept"),
            nodeEntity(id = "n2", name = "Beta", type = "person")
        )
        coEvery { kgNodeDao.getAll() } returns entities

        val result = repo.getAllNodes()

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo("n1")
        assertThat(result[0].label).isEqualTo("Alpha")
        assertThat(result[1].label).isEqualTo("Beta")
    }

    @Test
    fun `getAllEdges returns from dao`() = runTest {
        val entities = listOf(
            edgeEntity(id = "e1", sourceId = "a", targetId = "b", relation = "references")
        )
        coEvery { kgEdgeDao.getAll() } returns entities

        val result = repo.getAllEdges()

        assertThat(result).hasSize(1)
        assertThat(result[0].sourceId).isEqualTo("a")
        assertThat(result[0].targetId).isEqualTo("b")
        assertThat(result[0].relation).isEqualTo("references")
    }

    @Test
    fun `clear deletes edges then nodes`() = runTest {
        coEvery { kgEdgeDao.clearAll() } returns Unit
        coEvery { kgNodeDao.clearAll() } returns Unit

        repo.clear()

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            kgEdgeDao.clearAll()
            kgNodeDao.clearAll()
        }
    }

    @Test
    fun `getAllNodes returns empty when dao is empty`() = runTest {
        coEvery { kgNodeDao.getAll() } returns emptyList()

        val result = repo.getAllNodes()

        assertThat(result).isEmpty()
    }

    @Test
    fun `getAllEdges returns empty when dao is empty`() = runTest {
        coEvery { kgEdgeDao.getAll() } returns emptyList()

        val result = repo.getAllEdges()

        assertThat(result).isEmpty()
    }
}
