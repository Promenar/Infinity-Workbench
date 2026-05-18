package com.promenar.nexara.data.rag

import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

class GraphExtractor(
    private val protocol: LlmProtocol,
    private val graphStore: GraphStore,
    private val modelId: String? = null,
    private val systemPrompt: String = DEFAULT_KG_PROMPT,
    private val chunkSize: Int = 1200,
    private val chunkOverlap: Int = 200,
    /** 每个 chunk 抽取超时（毫秒），默认 120 秒 */
    private val timeoutMs: Long = 120_000L
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
            NexaraLogger.log("[RAG][GraphExtractor] Splitting text into ${chunks.size} chunks (chunkSize=$chunkSize, overlap=$chunkOverlap) for KG extraction.")

            // 重新抽取时先清除该文档的旧图谱数据，避免 weight 累加和重复边
            if (docId != null) {
                graphStore.clearGraphForDoc(docId)
            }

            val results = mutableListOf<ExtractionResult>()
            var failCount = 0
            var successCount = 0
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    NexaraLogger.log("[RAG][GraphExtractor] Extracting chunk ${index + 1}/${chunks.size} (${chunk.length} chars)")
                    val res = extractSingleChunk(chunk)
                    results.add(res)
                    if (res.error != null) {
                        failCount++
                        NexaraLogger.log("[RAG][GraphExtractor] Chunk ${index + 1}/${chunks.size} returned error: ${res.error}")
                    } else {
                        successCount++
                        NexaraLogger.log("[RAG][GraphExtractor] Chunk ${index + 1}/${chunks.size} OK: ${res.nodes.size} nodes, ${res.edges.size} edges")
                    }
                } catch (e: Exception) {
                    failCount++
                    NexaraLogger.logError("[RAG][GraphExtractor] chunk ${index + 1}/${chunks.size}", e)
                }
            }
            NexaraLogger.log("[RAG][GraphExtractor] Chunk summary: total=${chunks.size}, success=$successCount, failed=$failCount")

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

            // 后处理剪枝：过滤低质量节点和边
            val beforePrune = "${mergedNodes.size} nodes, ${mergedEdges.size} edges"
            pruneLowQuality(mergedNodes, mergedEdges)
            NexaraLogger.log("[RAG][GraphExtractor] Pruned: $beforePrune → ${mergedNodes.size} nodes, ${mergedEdges.size} edges")

            if (mergedNodes.isEmpty() && mergedEdges.isEmpty()) {
                return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "No nodes or edges extracted")
            }

            val nameToIdMap = mutableMapOf<String, String>()
            for (node in mergedNodes) {
                try {
                    val id = graphStore.upsertNode(node.name, node.type, node.metadata, scope)
                    nameToIdMap[node.name] = id
                } catch (e: Exception) {
                    NexaraLogger.log("[RAG][GraphExtractor] Node upsert failed for '${node.name}': ${e.message?.take(80)}")
                }
            }

            for (edge in mergedEdges) {
                val sourceId = nameToIdMap[edge.source]
                val targetId = nameToIdMap[edge.target]
                if (sourceId != null && targetId != null) {
                    try {
                        graphStore.createEdge(sourceId, targetId, edge.relation, docId, edge.weight, scope)
                    } catch (e: Exception) {
                        NexaraLogger.log("[RAG][GraphExtractor] Edge create failed '${edge.source}->${edge.target}': ${e.message?.take(80)}")
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

        val response = withTimeoutOrNull(timeoutMs) {
            protocol.sendPromptSync(request)
        }
        if (response == null) {
            NexaraLogger.log("[RAG][GraphExtractor] TIMEOUT after ${timeoutMs}ms for model=$effectiveModelId chunk=${chunkText.length}chars")
            return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "Timeout after ${timeoutMs / 1000}s")
        }
        val content = response.content

        if (content.isBlank()) {
            NexaraLogger.log("[RAG][GraphExtractor] EMPTY response from model=$effectiveModelId")
            return ExtractionResult(nodes = emptyList(), edges = emptyList(), error = "Empty response")
        }

        NexaraLogger.log("[RAG][GraphExtractor] LLM response received: ${content.length} chars, preview: ${content.take(150).replace("\n", "\\n")}")
        val result = parseExtractionResult(content)
        if (result == null || (result.nodes.isEmpty() && result.edges.isEmpty())) {
            val reason = if (result == null) "Failed to parse JSON" else "No entities extracted"
            NexaraLogger.log("[RAG][GraphExtractor] Parse result: $reason — raw response (first 300 chars): ${content.take(300).replace("\n", "\\n")}")
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
                NexaraLogger.log("[RAG][GraphExtractor] JSON fallback parse error: ${e2.message?.take(100)}")
                null
            }
        }
    }

    /**
     * 后处理剪枝：过滤低质量节点和边，保留高价值图谱结构。
     * 策略：
     * 1. 过滤名称过短（≤1 字符）或为纯数字的节点
     * 2. 过滤 weight < 0.3 的低置信度边
     * 3. 验证边的 source/target 必须指向现有节点，移除悬空边
     * 4. 移除无连接的孤立节点（没有任何边引用它）
     */
    private fun pruneLowQuality(
        nodes: MutableList<ExtractedNode>,
        edges: MutableList<ExtractedEdge>
    ) {
        // Step 1: 过滤低质量节点（名称过短或纯数字）
        nodes.removeAll { node ->
            val name = node.name.trim()
            name.length <= 1 || name.toDoubleOrNull() != null
        }

        // Step 2: 过滤低置信度边
        edges.removeAll { it.weight < 0.3 }

        // Step 3: 验证边的端点存在，移除悬空边
        val nodeNames = nodes.map { it.name.trim().lowercase() }.toSet()
        edges.removeAll { edge ->
            val s = edge.source.trim().lowercase()
            val t = edge.target.trim().lowercase()
            s !in nodeNames || t !in nodeNames
        }

        // Step 4: 移除孤立节点（无任何边连接）
        val connectedNodes = mutableSetOf<String>()
        for (edge in edges) {
            connectedNodes.add(edge.source.trim().lowercase())
            connectedNodes.add(edge.target.trim().lowercase())
        }
        val beforeIsolatedPrune = nodes.size
        nodes.removeAll { node ->
            node.name.trim().lowercase() !in connectedNodes
        }
        if (beforeIsolatedPrune != nodes.size) {
            NexaraLogger.log("[RAG][GraphExtractor] Removed ${beforeIsolatedPrune - nodes.size} isolated nodes")
        }
    }

    companion object {
        const val DEFAULT_KG_PROMPT = """You are an expert Knowledge Graph extractor.
Extract the most important entities and their relationships from the user provided text.

## Quality First
- Focus on CENTRAL entities that are core to the narrative or topic.
- Skip minor mentions, generic descriptors, abstract concepts, and fleeting references.
- Aim for 10-25 key entities and 15-35 relationships per chunk.
- Fewer high-quality extractions are better than many low-quality ones.

## Entity Types (use the most specific type that fits)
- person: Named characters or real people
- organization: Groups, factions, companies, teams
- location: Named places, cities, regions, buildings
- event: Specific plot events, historical incidents, milestones
- item: Named objects, artifacts, technologies, tools
- concept: Core themes, systems, rules, or doctrines (use sparingly)

## Output Format
Return a valid JSON object:
{
  "nodes": [
    { "name": "Exact Name", "type": "EntityType", "metadata": { "description": "one short sentence" } }
  ],
  "edges": [
    { "source": "SourceNodeName", "target": "TargetNodeName", "relation": "relationship_verb", "weight": 0.8 }
  ]
}

## Rules
1. Each "name" must be unique within nodes.
2. "source" and "target" in edges MUST match an existing node "name" exactly.
3. "relation" should be a concise verb phrase (e.g. "allies_with", "located_in", "leads_to").
4. "weight" (0.0-1.0): 0.9+=core relationship, 0.5-0.8=notable, below 0.5=skip it.
5. Do NOT extract every named entity — only those with meaningful relationships.
6. Return JSON only. No markdown code blocks, no explanation text."""
    }
}
