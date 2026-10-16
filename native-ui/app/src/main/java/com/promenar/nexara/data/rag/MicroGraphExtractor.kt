package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.KgJitCacheDao
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.session.SessionExecutionGate
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json

class MicroGraphExtractor(
    private val requestClient: KgRequestClient,
    private val graphStore: GraphStore,
    private val jitCacheDao: KgJitCacheDao,
    private val modelId: String? = null,
    private val systemPrompt: String = GraphExtractor.DEFAULT_KG_PROMPT,
    private val cacheTtlSeconds: Long = 3600,
    private val timeoutMs: Long = 5000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val executionGate: SessionExecutionGate? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val pendingMerges = Channel<PendingMerge>(capacity = 8)
    private val worker = scope.launch {
        for (pending in pendingMerges) {
            try {
                val merge: suspend () -> Unit = {
                    saveToCache(pending.key, pending.result)
                    merge(pending.result, pending.sessionId)
                }
                val gate = executionGate
                if (gate == null) merge() else gate.withSessionAdmission(pending.sessionId, merge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NexaraLogger.log("[KG] JIT 后台归并失败")
            }
        }
    }

    init {
        require(timeoutMs > 0 && cacheTtlSeconds > 0) { "JIT 超时和缓存有效期必须大于 0" }
    }

    suspend fun extract(
        topKResults: List<SearchResult>,
        query: String,
        sessionId: String,
        timeout: Long = timeoutMs,
        maxChars: Int = 6000,
    ): MicroGraphResult {
        require(timeout > 0 && maxChars > 0) { "JIT 超时和文本预算必须大于 0" }
        if (!scope.isActive) return MicroGraphResult.failure(KgExtractionStatus.CANCELLED, "JIT 抽取器已关闭", query)
        return withContext(dispatcher) { withTimeoutOrNull(timeout) {
            val text = prepareInputText(topKResults, maxChars)
            val key = kgSha256(listOf(sessionId, query, text, modelId.orEmpty(), systemPrompt,
                topKResults.joinToString(",") { it.id }, maxChars.toString(), "validation-v1").joinToString("\u0000"))
            getFromCache(key)?.let { return@withTimeoutOrNull it }
            val extracted = if (text.isBlank()) ExtractionResult(emptyList(), emptyList())
            else requestClient.extract(modelId, systemPrompt, "检索问题：$query\n\n请从以下文本片段提取关系：\n$text", timeout)
            if (!extracted.isSuccess) return@withTimeoutOrNull MicroGraphResult.failure(extracted.status, extracted.error.orEmpty(), query)
            val graph = ValidatedGraph.from(extracted.nodes, extracted.edges)
            val result = MicroGraphResult(graph.nodes, graph.edges, graph.context, topKResults.map { it.id }, query, System.currentTimeMillis())
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) return@withTimeoutOrNull MicroGraphResult.failure(KgExtractionStatus.CANCELLED, "JIT 抽取器已关闭", query)
            // 单个消费者与固定容量限制后台工作；队列繁忙时保留当前请求结果，跳过可选持久化。
            pendingMerges.trySend(PendingMerge(key, result, sessionId))
            result
        } ?: MicroGraphResult.failure(KgExtractionStatus.TIMEOUT, "知识图谱 JIT 抽取超时", query) }
    }

    private suspend fun getFromCache(key: String): MicroGraphResult? = try {
        val cached = jitCacheDao.getByKey(key)
        if (cached == null || cached.expiresAt < System.currentTimeMillis()) null else {
            val result = Json.decodeFromString<MicroGraphResult>(cached.resultJson)
            if (!result.isSuccess) null else {
                val graph = ValidatedGraph.from(result.nodes, result.edges)
                result.copy(nodes = graph.nodes, edges = graph.edges, context = graph.context)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    private suspend fun saveToCache(key: String, result: MicroGraphResult) {
        try {
            val now = System.currentTimeMillis()
            jitCacheDao.deleteExpired(now)
            jitCacheDao.insert(KgJitCacheEntity(
                cacheKey = key,
                queryHash = kgSha256(result.query),
                chunkIdsHash = kgSha256(result.sourceChunkIds.joinToString(",")),
                resultJson = Json.encodeToString(MicroGraphResult.serializer(), result),
                createdAt = now,
                expiresAt = now + cacheTtlSeconds * 1000,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NexaraLogger.log("[KG] JIT 缓存写入失败")
        }
    }

    private suspend fun merge(result: MicroGraphResult, sessionId: String) {
        val nodeIds = mutableMapOf<String, String>()
        for (node in result.nodes) {
            currentCoroutineContext().ensureActive()
            nodeIds[node.name] = graphStore.upsertNode(node.name, node.type, node.metadata, KgScope(sessionId = sessionId), "jit")
        }
        for (edge in result.edges) {
            currentCoroutineContext().ensureActive()
            graphStore.createEdge(
                sourceId = nodeIds.getValue(edge.source), targetId = nodeIds.getValue(edge.target),
                relation = edge.relation, weight = edge.weight, scope = KgScope(sessionId = sessionId), sourceType = "jit",
            )
        }
    }

    private fun prepareInputText(results: List<SearchResult>, maxChars: Int): String = buildString {
        for (result in results) {
            if (length >= maxChars) break
            if (isNotEmpty()) append("\n---\n".take(maxChars - length))
            append(result.content.take(maxChars - length))
        }
    }

    override fun close() {
        pendingMerges.close()
        scope.cancel()
    }

    private data class PendingMerge(val key: String, val result: MicroGraphResult, val sessionId: String)
}
