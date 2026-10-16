package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.mapper.toDomain
import com.promenar.nexara.domain.model.KgEdge
import com.promenar.nexara.domain.model.KgNode
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository

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
        kgEdgeDao.clearAll()
        kgNodeDao.clearAll()
    }
}
