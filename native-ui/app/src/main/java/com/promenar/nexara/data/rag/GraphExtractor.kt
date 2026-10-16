package com.promenar.nexara.data.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** 只构建候选图；文件图谱只能由文档索引事务提交。 */
class GraphExtractor(
    private val requestClient: KgRequestClient,
    private val graphStore: GraphStore,
    private val modelId: String? = null,
    private val systemPrompt: String = DEFAULT_KG_PROMPT,
    private val chunkSize: Int = 1200,
    private val chunkOverlap: Int = 200,
    private val timeoutMs: Long = 120_000L,
    private val checkpointRoot: File? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(chunkSize > 0 && chunkOverlap in 0 until chunkSize) { "知识图谱切块参数无效" }
        require(timeoutMs > 0) { "知识图谱超时必须大于 0" }
    }

    private val checkpoints: GraphExtractionCheckpoints
        get() = GraphExtractionCheckpoints(checkpointRoot ?: File(NexaraApplication.instance.cacheDir, "kg_extraction_checkpoint"))

    suspend fun extractCandidate(text: String, docId: String? = null, contentHash: String = kgSha256(text)): ExtractionResult =
        withContext(dispatcher) {
            val identity = GraphCheckpointIdentity(contentHash, kgSha256(text), modelId, kgSha256(systemPrompt), chunkSize, chunkOverlap)
            val store = docId?.let { checkpoints }
            if (docId != null) store?.prepare(docId, identity)
            val nodes = mutableListOf<ExtractedNode>()
            val edges = mutableListOf<ExtractedEdge>()
            for ((index, chunk) in splitText(text).withIndex()) {
                currentCoroutineContext().ensureActive()
                val cached = docId?.let { store?.load(it, identity, index, chunk) }
                val result = cached ?: requestClient.extract(modelId, systemPrompt, chunk, timeoutMs)
                currentCoroutineContext().ensureActive()
                if (!result.isSuccess) return@withContext result
                if (cached == null && docId != null) {
                    try {
                        store?.save(docId, identity, index, chunk, result)
                    } catch (_: Exception) {
                        // 断点是可丢弃缓存，失败不改变候选结果或旧图谱。
                        NexaraLogger.log("[KG] 断点写入失败")
                    }
                }
                nodes += result.nodes.take((KgGraphValidation.MAX_RAW_NODES - nodes.size).coerceAtLeast(0))
                edges += result.edges.take((KgGraphValidation.MAX_RAW_EDGES - edges.size).coerceAtLeast(0))
            }
            currentCoroutineContext().ensureActive()
            val graph = ValidatedGraph.from(nodes, edges)
            if (docId != null) store?.clear(docId)
            graph.toExtractionResult()
        }

    fun clearCheckpoints(docId: String) { checkpoints.clear(docId) }

    /** 遗留任务生命周期清理只作用于该文档，不执行全局清孤儿。 */
    suspend fun clearPersistedGraphForDoc(docId: String) {
        graphStore.clearGraphForDoc(docId)
        clearCheckpoints(docId)
    }

    private fun splitText(text: String): Sequence<String> = sequence {
        if (text.isBlank()) return@sequence
        var start = 0
        while (start < text.length) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            yield(text.substring(start, end))
            if (end == text.length) break
            start += chunkSize - chunkOverlap
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
