package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.mapper.toDomain
import com.promenar.nexara.data.rag.GraphExtractor
import com.promenar.nexara.domain.model.ExtractionResult
import com.promenar.nexara.domain.model.KgEdge
import com.promenar.nexara.domain.model.KgNode
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository

class KnowledgeGraphRepository(
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao,
    private val graphExtractor: GraphExtractor
) : IKnowledgeGraphRepository {

    override suspend fun extractFromContent(content: String, docId: String): ExtractionResult {
        if (content.isBlank()) {
            return ExtractionResult(emptyList(), emptyList())
        }

        val extractResult = graphExtractor.extractAndSave(
            text = content,
            docId = docId
        )

        if (extractResult.nodes.isEmpty() && extractResult.edges.isEmpty()) {
            return ExtractionResult(emptyList(), emptyList())
        }

        val nodeEntities = extractResult.nodes.mapNotNull { extractedNode ->
            kgNodeDao.getByName(extractedNode.name)
        }

        val edgeEntities = kgEdgeDao.getByDocId(docId)

        return ExtractionResult(
            nodes = nodeEntities.map { it.toDomain() },
            edges = edgeEntities.map { it.toDomain() }
        )
    }

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
