package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class KgMapperTest {

    private fun createNodeEntity(
        id: String = "n1",
        name: String = "TestNode",
        type: String = "concept",
        metadata: String? = null,
        createdAt: Long = 1000L
    ) = KgNodeEntity(
        id = id,
        name = name,
        type = type,
        metadata = metadata,
        createdAt = createdAt
    )

    private fun createEdgeEntity(
        id: String = "e1",
        sourceId: String = "n1",
        targetId: String = "n2",
        relation: String = "contains",
        weight: Double = 1.0,
        createdAt: Long = 1000L
    ) = KgEdgeEntity(
        id = id,
        sourceId = sourceId,
        targetId = targetId,
        relation = relation,
        weight = weight,
        createdAt = createdAt
    )

    @Test
    fun `toDomain maps node entity correctly`() {
        val entity = createNodeEntity(
            id = "node-1",
            name = "Kotlin",
            type = "concept"
        )
        val domain = entity.toDomain()

        assertThat(domain.id).isEqualTo("node-1")
        assertThat(domain.label).isEqualTo("Kotlin")
        assertThat(domain.type).isEqualTo("concept")
    }

    @Test
    fun `toDomain maps edge entity correctly`() {
        val entity = createEdgeEntity(
            id = "edge-1",
            sourceId = "a",
            targetId = "b",
            relation = "references",
            weight = 0.85
        )
        val domain = entity.toDomain()

        assertThat(domain.id).isEqualTo("edge-1")
        assertThat(domain.sourceId).isEqualTo("a")
        assertThat(domain.targetId).isEqualTo("b")
        assertThat(domain.relation).isEqualTo("references")
        assertThat(domain.weight).isWithin(0.001).of(0.85)
    }

    @Test
    fun `roundtrip node preserves label and type`() {
        val entity = createNodeEntity(name = "Android", type = "concept")
        val domain = entity.toDomain()

        assertThat(domain.label).isEqualTo("Android")
        assertThat(domain.type).isEqualTo("concept")
    }

    @Test
    fun `empty properties maps to emptyMap`() {
        val entity = createNodeEntity(metadata = null)
        val domain = entity.toDomain()

        assertThat(domain.properties).isEmpty()
    }

    @Test
    fun `metadata json maps to properties`() {
        val json = Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            mapOf("description" to "test node")
        )
        val entity = createNodeEntity(metadata = json)
        val domain = entity.toDomain()

        assertThat(domain.properties).hasSize(1)
        assertThat(domain.properties["description"]).isEqualTo("test node")
    }

    @Test
    fun `invalid metadata maps to emptyMap`() {
        val entity = createNodeEntity(metadata = "not-valid-json{{{")
        val domain = entity.toDomain()

        assertThat(domain.properties).isEmpty()
    }

    @Test
    fun `edge default weight is 1_0`() {
        val entity = createEdgeEntity(weight = 1.0)
        val domain = entity.toDomain()

        assertThat(domain.weight).isWithin(0.001).of(1.0)
    }
}
