package com.promenar.nexara.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagDetailsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test RagReference serialization with rerankScore and rankChange`() {
        val originalRef = RagReference(
            id = "chunk_123",
            content = "This is a test chunk.",
            source = "test_doc.md",
            score = 0.85f,
            rerankScore = 0.95f,
            rankChange = 2,
            chunkIndex = 1
        )

        val jsonString = json.encodeToString(originalRef)
        assertTrue(jsonString.contains("rerankScore"))
        assertTrue(jsonString.contains("0.95"))
        assertTrue(jsonString.contains("rankChange"))
        assertTrue(jsonString.contains("2"))

        val deserializedRef = json.decodeFromString<RagReference>(jsonString)
        assertEquals(originalRef.id, deserializedRef.id)
        assertEquals(originalRef.content, deserializedRef.content)
        assertEquals(originalRef.score, deserializedRef.score)
        assertEquals(originalRef.rerankScore, deserializedRef.rerankScore)
        assertEquals(originalRef.rankChange, deserializedRef.rankChange)
    }

    @Test
    fun `test KgPath serialization`() {
        val node1 = KgNode(id = "n1", label = "Nexara", type = "Software")
        val node2 = KgNode(id = "n2", label = "Android", type = "Platform")
        val edge = KgEdge(sourceId = "n1", targetId = "n2", relation = "runs_on")

        val path = KgPath(
            queryKeywords = listOf("Nexara", "Platform"),
            nodes = listOf(node1, node2),
            edges = listOf(edge),
            reasoning = "Found software running platform."
        )

        val jsonString = json.encodeToString(path)
        assertTrue(jsonString.contains("queryKeywords"))
        assertTrue(jsonString.contains("Nexara"))
        assertTrue(jsonString.contains("runs_on"))
        assertTrue(jsonString.contains("reasoning"))

        val deserializedPath = json.decodeFromString<KgPath>(jsonString)
        assertEquals(path.queryKeywords, deserializedPath.queryKeywords)
        assertEquals(path.nodes.size, deserializedPath.nodes.size)
        assertEquals(path.edges.size, deserializedPath.edges.size)
        assertEquals(path.edges[0].relation, deserializedPath.edges[0].relation)
        assertEquals(path.reasoning, deserializedPath.reasoning)
    }

    @Test
    fun `test Message serialization with kgPaths`() {
        val path = KgPath(
            queryKeywords = listOf("A"),
            nodes = listOf(KgNode("1", "A", "T")),
            edges = listOf()
        )

        val message = Message(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            content = "Hello",
            kgPaths = listOf(path)
        )

        val jsonString = json.encodeToString(message)
        assertTrue(jsonString.contains("kgPaths"))

        val deserializedMessage = json.decodeFromString<Message>(jsonString)
        assertEquals(1, deserializedMessage.kgPaths?.size)
        assertEquals("A", deserializedMessage.kgPaths?.get(0)?.queryKeywords?.get(0))
    }
}
