package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.KgEdgeDao
import com.promenar.infinity.data.local.db.dao.KgNodeDao
import com.promenar.infinity.data.mapper.toDomain
import com.promenar.infinity.domain.model.KgEdge
import com.promenar.infinity.domain.model.KgNode
import com.promenar.infinity.domain.repository.IKnowledgeGraphRepository

class KnowledgeGraphRepository(
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao,
) : IKnowledgeGraphRepository {

    override suspend fun getAllNodes(): List<KgNode> {
        return kgNodeDao.getAll().map { it.toDomain() }
    }

    override suspend fun getAllEdges(): List<KgEdge> {
        return kgEdgeDao.getAll().map { it.toDomain() }
    }

    override suspend fun getNodeCount(): Int =
        kgNodeDao.getCount()

    override suspend fun clear() {
        kgEdgeDao.clearGraph()
    }
}
