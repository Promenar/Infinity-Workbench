package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import kotlinx.serialization.json.*

class GraphExtractor(
    private val protocol: LlmProtocol,
    private val graphStore: GraphStore,
    private val modelId: String? = null,
    private val systemPrompt: String = DEFAULT_KG_PROMPT
) {
    suspend fun extractAndSave(
        text: String,
        docId: String? = null,
        scope: KgScope? = null
    ): ExtractionResult {
        return try {
            val effectiveModelId = modelId ?: "default"
            val request = PromptRequest(
                messages = listOf(
                    ProtocolMessage(role = "system", content = systemPrompt),
                    ProtocolMessage(role = "user", content = text)
                ),
                model = effectiveModelId,
                temperature = 0.0,
                stream = false
            )

            val response = protocol.sendPromptSync(request)
            val content = response.content

            if (content.isBlank()) {
                return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "Empty response")
            }

            val result = parseExtractionResult(content)
            if (result == null || result.nodes.isEmpty() && result.edges.isEmpty()) {
                return ExtractionResult(
                    nodes = emptyList(),
                    edges = emptyList(),
                    error = if (result == null) "Failed to parse JSON" else null
                )
            }

            val nameToIdMap = mutableMapOf<String, String>()
            for (node in result.nodes) {
                try {
                    val id = graphStore.upsertNode(node.name, node.type, node.metadata, scope)
                    nameToIdMap[node.name] = id
                } catch (e: Exception) {
                    // Continue with other nodes
                }
            }

            for (edge in result.edges) {
                val sourceId = nameToIdMap[edge.source]
                val targetId = nameToIdMap[edge.target]
                if (sourceId != null && targetId != null) {
                    try {
                        graphStore.createEdge(sourceId, targetId, edge.relation, docId, edge.weight, scope)
                    } catch (e: Exception) {
                        // Continue with other edges
                    }
                }
            }

            ExtractionResult(nodes = result.nodes, edges = result.edges)
        } catch (e: Exception) {
            ExtractionResult(nodes = emptyList(), edges = emptyList(), error = e.message?.take(80))
        }
    }

    private fun parseExtractionResult(content: String): ExtractionResult? {
        var jsonString = content.trim()

        val jsonBlockRegex = Regex("""```json\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        val genericBlockRegex = Regex("""```\s*([\s\S]*?)\s*```""")

        val jsonMatch = jsonBlockRegex.find(jsonString)
        val genericMatch = genericBlockRegex.find(jsonString)

        jsonString = when {
            jsonMatch != null -> jsonMatch.groupValues[1].trim()
            genericMatch != null -> {
                val potential = genericMatch.groupValues[1].trim()
                if (potential.startsWith("{")) potential else jsonString
            }
            else -> {
                val first = jsonString.indexOf('{')
                val last = jsonString.lastIndexOf('}')
                if (first != -1 && last != -1 && last > first) {
                    jsonString.substring(first, last + 1)
                } else jsonString
            }
        }

        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<ExtractionResult>(jsonString)
        } catch (e: Exception) {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonString).jsonObject
                val nodes = jsonObj["nodes"]?.jsonArray?.map { nodeEl ->
                    val obj = nodeEl.jsonObject
                    ExtractedNode(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        type = obj["type"]?.jsonPrimitive?.content ?: "concept",
                        metadata = obj["metadata"]?.toString()
                    )
                } ?: emptyList()

                val edges = jsonObj["edges"]?.jsonArray?.map { edgeEl ->
                    val obj = edgeEl.jsonObject
                    ExtractedEdge(
                        source = obj["source"]?.jsonPrimitive?.content ?: "",
                        target = obj["target"]?.jsonPrimitive?.content ?: "",
                        relation = obj["relation"]?.jsonPrimitive?.content ?: "",
                        weight = obj["weight"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
                    )
                } ?: emptyList()

                ExtractionResult(nodes = nodes, edges = edges)
            } catch (e2: Exception) {
                null
            }
        }
    }

    companion object {
        const val DEFAULT_KG_PROMPT = """You are an expert Knowledge Graph extractor.
Extract meaningful entities and relationships from the user provided text.

Target Entity Types: concept, person, org, location, event, product

Return a valid JSON object with the following structure:
{
  "nodes": [
    { "name": "Exact Name", "type": "EntityType", "metadata": { "description": "short desc" } }
  ],
  "edges": [
    { "source": "SourceNodeName", "target": "TargetNodeName", "relation": "relationship_verb", "weight": 1.0 }
  ]
}

Rules:
1. "name" must be the unique identifier.
2. "source" and "target" in edges must match a "name" in nodes.
3. Keep descriptions concise.
4. "weight" is 0.0 to 1.0, indicating confidence or importance.
5. JSON ONLY. No markdown formatted blocks."""
    }
}
