package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.domain.model.KgEdge
import com.promenar.nexara.domain.model.KgNode
import kotlinx.serialization.json.Json

private val mapperJson = Json { ignoreUnknownKeys = true }

fun KgNodeEntity.toDomain(): KgNode {
    val properties = try {
        metadata?.let {
            mapperJson.decodeFromString<Map<String, String>>(it)
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    return KgNode(
        id = id,
        label = name,
        type = type,
        properties = properties
    )
}

fun KgEdgeEntity.toDomain(): KgEdge = KgEdge(
    id = id,
    sourceId = sourceId,
    targetId = targetId,
    relation = relation,
    weight = weight
)
