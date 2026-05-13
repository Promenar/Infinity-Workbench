package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.ExtractionResult
import com.promenar.nexara.domain.model.KgNode
import com.promenar.nexara.domain.model.KgEdge

interface IKnowledgeGraphRepository {
    suspend fun extractFromDocument(documentId: String): ExtractionResult
    suspend fun getAllNodes(): List<KgNode>
    suspend fun getAllEdges(): List<KgEdge>
    suspend fun getNodeCount(): Int
    suspend fun clear()
}
