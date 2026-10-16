package com.promenar.nexara.data.rag

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val budget: KgExtractionBudget = KgExtractionBudget(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budgetRoot: File? = null,
) {
    init {
        require(chunkSize > 0 && chunkOverlap in 0 until chunkSize) { "知识图谱切块参数无效" }
        require(timeoutMs > 0) { "知识图谱超时必须大于 0" }
    }

    private val checkpoints: GraphExtractionCheckpoints
        get() = GraphExtractionCheckpoints(
            checkpointRoot ?: File(NexaraApplication.instance.cacheDir, "kg_extraction_checkpoint"),
            budgetRoot ?: checkpointRoot ?: File(NexaraApplication.instance.filesDir, "kg_extraction_budgets"),
        )

    suspend fun extractCandidate(
        text: String, docId: String? = null, contentHash: String = kgSha256(text),
        onProgress: suspend (KgExtractionProgress) -> Unit = {},
        retainCompletedCheckpoints: Boolean = false,
    ): ExtractionResult =
        withContext(dispatcher) {
            val identity = checkpointIdentity(text, contentHash)
            val store = docId?.let { checkpoints }
            if (docId != null) store?.prepare(docId, identity)
            val nodes = mutableListOf<ExtractedNode>()
            val edges = mutableListOf<ExtractedEdge>()
            val savedBudget = docId?.let { store?.loadBudget(it, identity) }
            var state = savedBudget ?: GraphExtractionBudgetState()
            val previousElapsed = state.elapsedMs
            val startedAt = nowMs()
            val totalChunks = KgExtractionBudget.chunkCount(text, chunkSize, chunkOverlap)
            var processedChunks = 0
            var budgetCleared = false
            fun elapsed() = previousElapsed + (nowMs() - startedAt).coerceAtLeast(0)
            fun progress(reason: KgExtractionStopReason? = null) =
                KgExtractionProgress(processedChunks, totalChunks, reason, state.calls, elapsed())
            fun persistBudget(reservedDurationMs: Long = 0) {
                state = state.copy(elapsedMs = elapsed())
                if (docId != null) store?.saveBudget(docId, identity, state.copy(elapsedMs = state.elapsedMs + reservedDurationMs))
            }
            suspend fun finish(reason: KgExtractionStopReason? = null): ExtractionResult {
                currentCoroutineContext().ensureActive()
                val finalProgress = progress(reason)
                if (reason != null || retainCompletedCheckpoints) persistBudget() else if (docId != null) {
                    store?.clear(docId, identity)
                    budgetCleared = true
                }
                onProgress(finalProgress)
                return ValidatedGraph.from(nodes, edges).toExtractionResult().copy(
                    status = if (reason == null) KgExtractionStatus.SUCCESS else KgExtractionStatus.PARTIAL,
                    progress = finalProgress,
                    usage = state.usage,
                )
            }
            try {
                onProgress(progress())
                for ((index, chunk) in splitText(text).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val cached = docId?.let { store?.load(it, identity, index, chunk) }
                    val result = if (cached != null) {
                        // 旧断点没有预算账本时，成功分块也必须计入文档预算。
                        if (savedBudget == null) state = state.copy(
                            calls = state.calls + 1,
                            outputTokens = state.outputTokens + cached.usage.outputTokens.takeIf { it > 0 }.let { it ?: budget.requestMaxOutputTokens.toLong() },
                            usage = state.usage + cached.usage,
                        )
                        cached
                    } else {
                        val reason = when {
                            state.calls >= budget.maxCalls -> KgExtractionStopReason.CALLS
                            state.outputTokens >= budget.maxOutputTokens -> KgExtractionStopReason.OUTPUT_TOKENS
                            elapsed() >= budget.maxDurationMs -> KgExtractionStopReason.DURATION
                            else -> null
                        }
                        if (reason != null) return@withContext finish(reason)
                        val requestLimit = minOf(budget.requestMaxOutputTokens.toLong(), budget.maxOutputTokens - state.outputTokens).toInt()
                        val remainingMs = (budget.maxDurationMs - elapsed()).coerceAtLeast(1)
                        // 请求前预留最坏输出，失败或进程中断也不能悄悄重置已发生的费用。
                        state = state.copy(calls = state.calls + 1, outputTokens = state.outputTokens + requestLimit)
                        // 在途请求先预留超时时长，硬终止进程也不会获得新的时长额度。
                        persistBudget(minOf(timeoutMs, remainingMs))
                        val response = requestClient.extract(modelId, systemPrompt, chunk, minOf(timeoutMs, remainingMs), requestLimit)
                        currentCoroutineContext().ensureActive()
                        val hasResponse = response.isSuccess || response.status == KgExtractionStatus.PARSE_FAILURE
                        val chargedOutput = if (hasResponse) {
                            if (response.usage.estimated) maxOf(requestLimit.toLong(), response.usage.outputTokens) else response.usage.outputTokens
                        } else requestLimit.toLong()
                        state = state.copy(
                            outputTokens = state.outputTokens - requestLimit + chargedOutput,
                            usage = state.usage + if (hasResponse) response.usage else KgTokenUsage(estimated = true),
                        )
                        persistBudget()
                        if (response.status == KgExtractionStatus.TIMEOUT && elapsed() >= budget.maxDurationMs) {
                            return@withContext finish(KgExtractionStopReason.DURATION)
                        }
                        response
                    }
                    currentCoroutineContext().ensureActive()
                    if (!result.isSuccess) return@withContext result.copy(progress = progress(), usage = state.usage)
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
                    processedChunks++
                    onProgress(progress())
                }
                finish()
            } finally {
                if (docId != null && !budgetCleared) {
                    // 取消仍消耗在途时长，恢复时不能重新获得这部分预算。
                    withContext(NonCancellable + dispatcher) {
                        try { persistBudget() } catch (_: Exception) { NexaraLogger.log("[KG] 预算账本写入失败") }
                    }
                }
            }
        }

    fun clearCheckpoints(docId: String) { checkpoints.clear(docId) }

    fun clearCommittedCheckpoints(text: String, docId: String, contentHash: String) {
        checkpoints.clear(docId, checkpointIdentity(text, contentHash))
    }

    private fun checkpointIdentity(text: String, contentHash: String) =
        GraphCheckpointIdentity(contentHash, kgSha256(text), modelId, kgSha256(systemPrompt), chunkSize, chunkOverlap)

    fun estimate(text: String): KgExtractionEstimate = budget.estimate(text, chunkSize, chunkOverlap, systemPrompt)

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
