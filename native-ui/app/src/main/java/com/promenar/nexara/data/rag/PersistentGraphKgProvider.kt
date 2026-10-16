package com.promenar.nexara.data.rag

import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.manager.KgContextResult
import com.promenar.nexara.ui.chat.manager.KgProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 持久图优先，只有可见命中片段且图覆盖不足时才消耗 JIT 请求。 */
class PersistentGraphKgProvider(
    graphStore: GraphStore,
    private val jit: KgProvider,
    private val configProvider: () -> RagConfiguration,
) : KgProvider {
    private val database = graphStore.database
    private val retrieval = GraphRetrievalStore(graphStore.database)

    override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>) =
        retrieveContext(query, sessionId, topKResults, RagOptions(enableKnowledgeGraph = true))

    override suspend fun retrieveContext(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions): KgContextResult {
        if (options.enableKnowledgeGraph != true) return KgContextResult("")
        val config = configProvider()
        val scope = KgRetrievalScope(sessionId, options.activeDocIds, options.activeFolderIds, options.isGlobal)
        val documents = retrieval.visibleDocumentIds(scope)
        val visibleReferences = withContext(Dispatchers.IO) {
            topKResults.filter { reference ->
                if (reference.documentId != null) reference.documentId in documents
                else database.vectorDao().getById(reference.id)?.let { vector ->
                    vector.docId == null && vector.sessionId == sessionId && !vector.stale && vector.content == reference.content
                } == true
            }
        }
        val persistent = retrieval.retrieve(query, scope, visibleReferences.mapNotNull { it.documentId }, config)
        val needsJit = persistent.seedCount < config.kgJitMinSeeds.coerceIn(1, 32) || persistent.paths.isEmpty()
        val instant = if (config.kgJitFallbackEnabled && needsJit && visibleReferences.isNotEmpty()) {
            try {
                jit.extractContext(query, sessionId, visibleReferences)?.takeIf { it.status == KgExtractionStatus.SUCCESS && it.error == null }
                    ?.paths.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        val paths = KgContextBudget.prune(KgContextBudget.fuse(persistent.paths, instant), config.kgMaxNodes, config.kgMaxEdges)
        val context = KgContextBudget.fit(paths, config.kgContextMaxTokens.coerceIn(0, 16384))
        return KgContextResult(context.context, context.paths, canonicalPaths = true,
            maxContextTokens = config.kgContextMaxTokens.coerceIn(0, 16384))
    }
}
