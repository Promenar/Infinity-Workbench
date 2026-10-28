package com.promenar.infinity.domain.repository

import com.promenar.infinity.domain.model.KgNode
import com.promenar.infinity.domain.model.KgEdge

interface IKnowledgeGraphRepository {
    suspend fun getAllNodes(): List<KgNode>
    suspend fun getAllEdges(): List<KgEdge>
    suspend fun getNodeCount(): Int
    suspend fun clear()
}
