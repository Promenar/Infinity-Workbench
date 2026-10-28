package com.promenar.infinity.data.mapper

import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.domain.model.KgEdge
import com.promenar.infinity.domain.model.KgNode
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
