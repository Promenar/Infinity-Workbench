package com.promenar.nexara.domain.usecase

import android.content.SharedPreferences
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.rag.RagConfiguration

class RagConfigPersistence(
    private val prefs: SharedPreferences
) {

    /** P0 修复: 将 SharedPreferences 中的用户配置直接构建为 RagConfiguration
     *  解决 MemoryManager 始终使用硬编码默认值的问题 */
    fun loadFullConfig(): RagConfiguration {
        val retrieval = loadRetrievalConfig()
        val rag = loadRagConfig()
        return RagConfiguration(
            enableMemory = retrieval.enableMemory,
            enableDocs = retrieval.enableDocs,
            enableKnowledgeGraph = retrieval.enableKnowledgeGraph,
            enableQueryRewrite = retrieval.enableQueryRewrite,
            enableHybridSearch = retrieval.enableHybridSearch,
            enableRerank = retrieval.enableRerank,
            enableIncrementalHash = prefs.getBoolean(KEY_ENABLE_INCREMENTAL_HASH, true),
            enableLocalPreprocess = prefs.getBoolean(KEY_ENABLE_LOCAL_PREPROCESS, false),
            memoryLimit = retrieval.memoryLimit,
            memoryThreshold = retrieval.memoryThreshold,
            docLimit = retrieval.docLimit,
            docThreshold = retrieval.docThreshold,
            docChunkSize = rag.docChunkSize,
            chunkOverlap = rag.chunkOverlap,
            memoryChunkSize = rag.memoryChunkSize,
            rerankTopK = retrieval.rerankTopK,
            rerankFinalK = retrieval.rerankFinalK,
            rerankMaxPerCall = prefs.getInt(KEY_RERANK_MAX_PER_CALL, 100),
            hybridAlpha = retrieval.hybridAlpha,
            hybridBM25Boost = retrieval.hybridBM25Boost,
            kgExtractionModel = retrieval.kgExtractionModel,
            kgExtractionPrompt = retrieval.kgExtractionPrompt,
            kgFreeMode = retrieval.kgFreeMode,
            kgDomainAuto = retrieval.kgDomainAuto,
            kgExtractionTimeoutSeconds = retrieval.kgExtractionTimeoutSeconds,
            queryRewriteStrategy = retrieval.queryRewriteStrategy,
            queryRewriteModel = retrieval.queryRewriteModel,
            queryRewriteCount = retrieval.queryRewriteCount,
            jitMaxChunks = retrieval.jitMaxChunks,
            summaryTemplate = rag.summaryTemplate,
            currentPreset = rag.currentPreset
        )
    }
    fun loadRagConfig(): AgentRagConfig {
        return AgentRagConfig(
            docChunkSize = prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE),
            chunkOverlap = prefs.getInt(KEY_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP),
            memoryChunkSize = prefs.getInt(KEY_MEMORY_CHUNK_SIZE, DEFAULT_MEMORY_CHUNK_SIZE),
            summaryTemplate = prefs.getString(KEY_SUMMARY_TEMPLATE, "") ?: "",
            currentPreset = prefs.getString(KEY_CURRENT_PRESET, null)
        )
    }

    fun saveRagConfig(config: AgentRagConfig) {
        prefs.edit()
            .putInt(KEY_CHUNK_SIZE, config.docChunkSize)
            .putInt(KEY_CHUNK_OVERLAP, config.chunkOverlap)
            .putInt(KEY_MEMORY_CHUNK_SIZE, config.memoryChunkSize)
            .putString(KEY_SUMMARY_TEMPLATE, config.summaryTemplate)
            .putString(KEY_CURRENT_PRESET, config.currentPreset)
            .apply()
    }

    fun loadRetrievalConfig(): AgentRetrievalConfig {
        return AgentRetrievalConfig(
            memoryLimit = prefs.getInt(KEY_MEMORY_LIMIT, DEFAULT_MEMORY_LIMIT),
            memoryThreshold = prefs.getFloat(KEY_MEMORY_THRESHOLD, DEFAULT_MEMORY_THRESHOLD),
            docLimit = prefs.getInt(KEY_DOC_LIMIT, DEFAULT_DOC_LIMIT),
            docThreshold = prefs.getFloat(KEY_DOC_THRESHOLD, DEFAULT_DOC_THRESHOLD),
            enableRerank = prefs.getBoolean(KEY_ENABLE_RERANK, true),
            rerankTopK = prefs.getInt(KEY_RERANK_TOP_K, DEFAULT_RERANK_TOP_K),
            rerankFinalK = prefs.getInt(KEY_RERANK_FINAL_K, DEFAULT_RERANK_FINAL_K),
            enableQueryRewrite = prefs.getBoolean(KEY_ENABLE_QUERY_REWRITE, true),
            queryRewriteStrategy = prefs.getString(KEY_QUERY_REWRITE_STRATEGY, DEFAULT_QUERY_REWRITE_STRATEGY)
                ?: DEFAULT_QUERY_REWRITE_STRATEGY,
            queryRewriteCount = prefs.getInt(KEY_QUERY_REWRITE_COUNT, DEFAULT_QUERY_REWRITE_COUNT),
            enableHybridSearch = prefs.getBoolean(KEY_ENABLE_HYBRID_SEARCH, true),
            hybridAlpha = prefs.getFloat(KEY_HYBRID_ALPHA, DEFAULT_HYBRID_ALPHA),
            hybridBM25Boost = prefs.getFloat(KEY_HYBRID_BM25_BOOST, DEFAULT_HYBRID_BM25_BOOST),
            enableMemory = prefs.getBoolean(KEY_ENABLE_MEMORY, true),
            enableDocs = prefs.getBoolean(KEY_ENABLE_DOCS, true),
            enableKnowledgeGraph = prefs.getBoolean(KEY_ENABLE_KG, false),
            queryRewriteModel = prefs.getString(KEY_QUERY_REWRITE_MODEL, null),
            kgExtractionModel = prefs.getString(KEY_KG_MODEL, null),
            kgExtractionPrompt = prefs.getString(KEY_KG_PROMPT, null),
            kgEntityTypes = emptyList(),
            kgFreeMode = prefs.getBoolean(KEY_KG_FREE_MODE, false),
            kgDomainAuto = prefs.getBoolean(KEY_KG_DOMAIN_AUTO, false),
            kgDomainHint = null,
            kgExtractionTimeoutSeconds = prefs.getInt(KEY_KG_EXTRACTION_TIMEOUT, DEFAULT_KG_EXTRACTION_TIMEOUT),
            jitMaxChunks = prefs.getInt(KEY_JIT_MAX_CHUNKS, 0)
        )
    }

    fun saveRetrievalConfig(config: AgentRetrievalConfig) {
        prefs.edit()
            .putInt(KEY_MEMORY_LIMIT, config.memoryLimit)
            .putFloat(KEY_MEMORY_THRESHOLD, config.memoryThreshold)
            .putInt(KEY_DOC_LIMIT, config.docLimit)
            .putFloat(KEY_DOC_THRESHOLD, config.docThreshold)
            .putBoolean(KEY_ENABLE_RERANK, config.enableRerank)
            .putInt(KEY_RERANK_TOP_K, config.rerankTopK)
            .putInt(KEY_RERANK_FINAL_K, config.rerankFinalK)
            .putBoolean(KEY_ENABLE_QUERY_REWRITE, config.enableQueryRewrite)
            .putString(KEY_QUERY_REWRITE_STRATEGY, config.queryRewriteStrategy)
            .putInt(KEY_QUERY_REWRITE_COUNT, config.queryRewriteCount)
            .putBoolean(KEY_ENABLE_HYBRID_SEARCH, config.enableHybridSearch)
            .putFloat(KEY_HYBRID_ALPHA, config.hybridAlpha)
            .putFloat(KEY_HYBRID_BM25_BOOST, config.hybridBM25Boost)
            .putBoolean(KEY_ENABLE_MEMORY, config.enableMemory)
            .putBoolean(KEY_ENABLE_DOCS, config.enableDocs)
            .putBoolean(KEY_ENABLE_KG, config.enableKnowledgeGraph)
            .putString(KEY_QUERY_REWRITE_MODEL, config.queryRewriteModel)
            .putString(KEY_KG_MODEL, config.kgExtractionModel)
            .putString(KEY_KG_PROMPT, config.kgExtractionPrompt)
            .putBoolean(KEY_KG_FREE_MODE, config.kgFreeMode)
            .putBoolean(KEY_KG_DOMAIN_AUTO, config.kgDomainAuto)
            .putInt(KEY_KG_EXTRACTION_TIMEOUT, config.kgExtractionTimeoutSeconds)
            .putInt(KEY_JIT_MAX_CHUNKS, config.jitMaxChunks)
            .apply()
    }

    companion object {
        const val KEY_CHUNK_SIZE = "doc_chunk_size"
        const val KEY_CHUNK_OVERLAP = "chunk_overlap"
        const val KEY_MEMORY_CHUNK_SIZE = "memory_chunk_size"
        const val KEY_SUMMARY_TEMPLATE = "summary_template"
        const val KEY_CURRENT_PRESET = "current_preset"
        const val KEY_MEMORY_LIMIT = "memory_limit"
        const val KEY_MEMORY_THRESHOLD = "memory_threshold"
        const val KEY_DOC_LIMIT = "doc_limit"
        const val KEY_DOC_THRESHOLD = "doc_threshold"
        const val KEY_ENABLE_RERANK = "enable_rerank"
        const val KEY_RERANK_TOP_K = "rerank_top_k"
        const val KEY_RERANK_FINAL_K = "rerank_final_k"
        const val KEY_RERANK_MAX_PER_CALL = "rerank_max_per_call"
        const val KEY_ENABLE_QUERY_REWRITE = "enable_query_rewrite"
        const val KEY_QUERY_REWRITE_STRATEGY = "query_rewrite_strategy"
        const val KEY_QUERY_REWRITE_COUNT = "query_rewrite_count"
        const val KEY_QUERY_REWRITE_MODEL = "query_rewrite_model"
        const val KEY_ENABLE_HYBRID_SEARCH = "enable_hybrid_search"
        const val KEY_HYBRID_ALPHA = "hybrid_alpha"
        const val KEY_HYBRID_BM25_BOOST = "hybrid_bm25_boost"
        const val KEY_ENABLE_MEMORY = "enable_memory"
        const val KEY_ENABLE_DOCS = "enable_docs"
        const val KEY_ENABLE_KG = "enable_kg"
        const val KEY_KG_MODEL = "kg_model"
        const val KEY_KG_PROMPT = "kg_prompt"
        const val KEY_KG_FREE_MODE = "kg_free_mode"
        const val KEY_KG_DOMAIN_AUTO = "kg_domain_auto"
        const val KEY_KG_EXTRACTION_TIMEOUT = "kg_extraction_timeout"
        const val KEY_JIT_MAX_CHUNKS = "jit_max_chunks"
        const val KEY_ENABLE_INCREMENTAL_HASH = "enable_incremental_hash"
        const val KEY_ENABLE_LOCAL_PREPROCESS = "enable_local_preprocess"
        const val KEY_COST_STRATEGY = "cost_strategy"
        const val KEY_SHOW_RETRIEVAL_PROGRESS = "show_retrieval_progress"
        const val KEY_SHOW_RETRIEVAL_DETAILS = "show_retrieval_details"
        const val KEY_TRACK_RETRIEVAL_METRICS = "track_retrieval_metrics"

        const val DEFAULT_CHUNK_SIZE = 800
        const val DEFAULT_CHUNK_OVERLAP = 100
        const val DEFAULT_MEMORY_CHUNK_SIZE = 1000
        const val DEFAULT_MEMORY_LIMIT = 5
        const val DEFAULT_MEMORY_THRESHOLD = 0.7f
        const val DEFAULT_DOC_LIMIT = 8
        const val DEFAULT_DOC_THRESHOLD = 0.45f
        const val DEFAULT_RERANK_TOP_K = 30
        const val DEFAULT_RERANK_FINAL_K = 5
        const val DEFAULT_QUERY_REWRITE_STRATEGY = "multi-query"
        const val DEFAULT_QUERY_REWRITE_COUNT = 3
        const val DEFAULT_HYBRID_ALPHA = 0.6f
        const val DEFAULT_HYBRID_BM25_BOOST = 1.0f
        const val DEFAULT_KG_EXTRACTION_TIMEOUT = 120
    }
}
