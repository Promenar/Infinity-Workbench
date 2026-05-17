package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.serialization.json.*

class GraphExtractor(
    private val protocol: LlmProtocol,
    private val graphStore: GraphStore,
    private val modelId: String? = null,
    private val systemPrompt: String = DEFAULT_KG_PROMPT,
    private val chunkSize: Int = 1200,
    private val chunkOverlap: Int = 200
) {
    private fun splitText(text: String): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }
        val chunks = mutableListOf<String>()
        var start = 0
        val step = chunkSize - chunkOverlap
        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            chunks.add(text.substring(start, end))
            if (end == text.length) break
            start += step
        }
        return chunks
    }
    suspend fun extractAndSave(
        text: String,
        docId: String? = null,
        scope: KgScope? = null
    ): ExtractionResult {
        return try {
            val chunks = splitText(text)
            NexaraLogger.log("GraphExtractor: Splitting text into ${chunks.size} chunks (chunkSize=$chunkSize, overlap=$chunkOverlap) for KG extraction.")

            val results = mutableListOf<ExtractionResult>()
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    NexaraLogger.log("GraphExtractor: Extracting chunk ${index + 1}/${chunks.size} (${chunk.length} chars)")
                    val res = extractSingleChunk(chunk)
                    results.add(res)
                } catch (e: Exception) {
                    NexaraLogger.log("GraphExtractor: Extraction failed for chunk ${index + 1}: ${e.message?.take(80)}")
                }
            }

            if (results.isEmpty()) {
                return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "All chunks failed extraction")
            }

            // 合并与不区分大小写去重
            val mergedNodes = mutableListOf<ExtractedNode>()
            val mergedEdges = mutableListOf<ExtractedEdge>()
            val nodeKeys = mutableSetOf<String>()
            val edgeKeys = mutableSetOf<String>()

            for (res in results) {
                for (node in res.nodes) {
                    val key = node.name.trim().lowercase()
                    if (key.isNotEmpty() && !nodeKeys.contains(key)) {
                        nodeKeys.add(key)
                        mergedNodes.add(node)
                    }
                }
                for (edge in res.edges) {
                    val sKey = edge.source.trim().lowercase()
                    val tKey = edge.target.trim().lowercase()
                    val rKey = edge.relation.trim().lowercase()
                    val key = "$sKey|$tKey|$rKey"
                    if (sKey.isNotEmpty() && tKey.isNotEmpty() && !edgeKeys.contains(key)) {
                        edgeKeys.add(key)
                        mergedEdges.add(edge)
                    }
                }
            }

            NexaraLogger.log("GraphExtractor: Merged KG results, unique nodes=${mergedNodes.size}, unique edges=${mergedEdges.size}")

            if (mergedNodes.isEmpty() && mergedEdges.isEmpty()) {
                return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "No nodes or edges extracted")
            }

            val nameToIdMap = mutableMapOf<String, String>()
            for (node in mergedNodes) {
                try {
                    val id = graphStore.upsertNode(node.name, node.type, node.metadata, scope)
                    nameToIdMap[node.name] = id
                } catch (e: Exception) {
                    NexaraLogger.log("GraphExtractor: Node upsert failed for '${node.name}': ${e.message?.take(80)}")
                }
            }

            for (edge in mergedEdges) {
                val sourceId = nameToIdMap[edge.source]
                val targetId = nameToIdMap[edge.target]
                if (sourceId != null && targetId != null) {
                    try {
                        graphStore.createEdge(sourceId, targetId, edge.relation, docId, edge.weight, scope)
                    } catch (e: Exception) {
                        NexaraLogger.log("GraphExtractor: Edge create failed '${edge.source}->${edge.target}': ${e.message?.take(80)}")
                    }
                }
            }

            ExtractionResult(nodes = mergedNodes, edges = mergedEdges)
        } catch (e: Exception) {
            NexaraLogger.logError("GraphExtractor.extractAndSave", e)
            ExtractionResult(nodes = emptyList(), edges = emptyList(), error = e.message?.take(80))
        }
    }

    private suspend fun extractSingleChunk(chunkText: String): ExtractionResult {
        val effectiveModelId = modelId ?: "default"
        val request = PromptRequest(
            messages = listOf(
                ProtocolMessage(role = "system", content = systemPrompt),
                ProtocolMessage(role = "user", content = chunkText)
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
        if (result == null || (result.nodes.isEmpty() && result.edges.isEmpty())) {
            return ExtractionResult(
                nodes = emptyList(),
                edges = emptyList(),
                error = if (result == null) "Failed to parse JSON" else null
            )
        }
        return result
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
                NexaraLogger.log("GraphExtractor: JSON fallback parse error: ${e2.message?.take(100)}")
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
