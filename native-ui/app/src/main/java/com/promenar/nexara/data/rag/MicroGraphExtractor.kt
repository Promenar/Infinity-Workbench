package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.KgJitCacheDao
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MicroGraphExtractor(
    private val protocol: LlmProtocol,
    private val graphStore: GraphStore,
    private val jitCacheDao: KgJitCacheDao,
    private val modelId: String? = null,
    private val systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT,
    private val cacheTtlSeconds: Long = 3600
) {
    suspend fun extract(
        topKResults: List<SearchResult>,
        query: String,
        sessionId: String,
        timeout: Long = 5000,
        maxChars: Int = 6000
    ): MicroGraphResult? {
        val cacheKey = generateCacheKey(query, topKResults)

        val cached = getFromCache(cacheKey)
        if (cached != null) return cached

        val textToAnalyze = prepareInputText(topKResults, maxChars)
        if (textToAnalyze.isBlank()) return null

        val extractionResult = performExtraction(textToAnalyze, query)
        if (extractionResult == null) return null

        val fullResult = MicroGraphResult(
            nodes = extractionResult.nodes,
            edges = extractionResult.edges,
            context = extractionResult.edges.joinToString("\n") { edge ->
                "- ${edge.source} --[${edge.relation}]--> ${edge.target}"
            },
            sourceChunkIds = topKResults.map { it.id },
            query = query,
            extractedAt = System.currentTimeMillis()
        )

        saveToCache(cacheKey, fullResult)
        backgroundMerge(fullResult, sessionId)

        return fullResult
    }

    private suspend fun getFromCache(key: String): MicroGraphResult? {
        try {
            jitCacheDao.deleteExpired(System.currentTimeMillis())
            val cached = jitCacheDao.getByKey(key) ?: return null
            return try {
                Json.decodeFromString<MicroGraphResult>(cached.resultJson)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun saveToCache(key: String, result: MicroGraphResult) {
        try {
            val now = System.currentTimeMillis()
            val expiresAt = now + cacheTtlSeconds * 1000
            val json = Json.encodeToString(MicroGraphResult.serializer(), result)
            jitCacheDao.insert(
                KgJitCacheEntity(
                    cacheKey = key,
                    queryHash = result.query,
                    chunkIdsHash = result.sourceChunkIds.joinToString(","),
                    resultJson = json,
                    createdAt = now,
                    expiresAt = expiresAt
                )
            )
        } catch (e: Exception) {
            // Cache save failure is non-critical
        }
    }

    private suspend fun performExtraction(text: String, query: String): ExtractionResult? {
        return try {
            val effectiveModelId = modelId ?: "default"
            val request = PromptRequest(
                messages = listOf(
                    ProtocolMessage(role = "system", content = systemPrompt),
                    ProtocolMessage(
                        role = "user",
                        content = """Query Context: "$query"

Analyze the following text fragments and build a micro-graph of related entities:

$text"""
                    )
                ),
                model = effectiveModelId,
                temperature = 0.0,
                stream = false
            )

            val response = protocol.sendPromptSync(request)
            val content = response.content
            if (content.isBlank()) return null

            parseJsonFromContent(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJsonFromContent(content: String): ExtractionResult? {
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
            val jsonEl = kotlinx.serialization.json.Json.parseToJsonElement(jsonString).jsonObject

            val nodes = jsonEl["nodes"]?.jsonArray?.map { nodeEl ->
                val obj = nodeEl.jsonObject
                ExtractedNode(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "concept",
                    metadata = obj["metadata"]?.toString()
                )
            } ?: emptyList()

            val edges = jsonEl["edges"]?.jsonArray?.map { edgeEl ->
                val obj = edgeEl.jsonObject
                ExtractedEdge(
                    source = obj["source"]?.jsonPrimitive?.content ?: "",
                    target = obj["target"]?.jsonPrimitive?.content ?: "",
                    relation = obj["relation"]?.jsonPrimitive?.content ?: "",
                    weight = obj["weight"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
                )
            } ?: emptyList()

            if (nodes.isEmpty() && edges.isEmpty()) return null
            ExtractionResult(nodes = nodes, edges = edges)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun backgroundMerge(result: MicroGraphResult, sessionId: String) {
        try {
            val nameToIdMap = mutableMapOf<String, String>()

            for (node in result.nodes) {
                val id = graphStore.upsertNode(
                    name = node.name,
                    type = node.type,
                    metadata = node.metadata,
                    scope = KgScope(sessionId = sessionId),
                    sourceType = "jit"
                )
                nameToIdMap[node.name] = id
            }

            for (edge in result.edges) {
                val sourceId = nameToIdMap[edge.source]
                val targetId = nameToIdMap[edge.target]
                if (sourceId != null && targetId != null) {
                    graphStore.createEdge(
                        sourceId = sourceId,
                        targetId = targetId,
                        relation = edge.relation,
                        docId = null,
                        weight = edge.weight,
                        scope = KgScope(sessionId = sessionId),
                        sourceType = "jit"
                    )
                }
            }
        } catch (e: Exception) {
            // Background merge failure is non-critical
        }
    }

    private fun generateCacheKey(query: String, results: List<SearchResult>): String {
        val ids = results.map { it.id }.sorted().joinToString(",")
        return "$query:$ids"
    }

    private fun prepareInputText(results: List<SearchResult>, maxChars: Int): String {
        val sb = StringBuilder()
        for (res in results) {
            val remaining = maxChars - sb.length
            if (remaining <= 0) break
            sb.append(res.content.take(remaining))
            sb.append("\n---\n")
        }
        return sb.toString().trim()
    }
}
